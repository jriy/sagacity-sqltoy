/**
 * 
 */
package org.sagacity.sqltoy.dialect.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sagacity.sqltoy.SqlToyContext;
import org.sagacity.sqltoy.config.model.PageOptimize;
import org.sagacity.sqltoy.config.model.SqlToyConfig;
import org.sagacity.sqltoy.executor.QueryExecutor;
import org.sagacity.sqltoy.model.QueryExecutorExtend;
import org.sagacity.sqltoy.utils.CollectionUtil;

/**
 * @project sagacity-sqltoy4.0
 * @description 提供分页优化缓存实现，记录相同查询条件的总记录数,采用FIFO算法保留符合活跃时间和记录规模
 * @author chenrenfei <a href="mailto:zhongxuchen@gmail.com">联系作者</a>
 * @version id:PageOptimizeUtils.java,Revision:v1.0,Date:2016年11月24日
 * @modify 2020-8-4 修改原本只支持xml中必须有id的sql才能缓存的策略,便于今后直接从代码中实现分页优化功能
 */
public class PageOptimizeUtils {
	private static final int INITIAL_CAPACITY = 128;
	private static final float LOAD_FACTOR = 0.75f;

	// 定义sql对应的分页查询请求(不同查询条件构成的key)对应的总记录数Object[] as
	// {expireTime(失效时间),recordCount(分页查询总记录数)}
	private static ConcurrentHashMap<String, LinkedHashMap<String, Object[]>> pageOptimizeCache = new ConcurrentHashMap<String, LinkedHashMap<String, Object[]>>(
			INITIAL_CAPACITY, LOAD_FACTOR);

	/**
	 * @todo 根据查询条件组成key
	 * @param sqlToyContext
	 * @param sqlToyConfig
	 * @param queryExecutor
	 * @param pageOptimize
	 * @return
	 * @throws Exception
	 */
	public static String generateOptimizeKey(final SqlToyContext sqlToyContext, final SqlToyConfig sqlToyConfig,
			final QueryExecutor queryExecutor, PageOptimize pageOptimize) throws Exception {
		// 没有开放分页优化或sql id为null都不执行优化操作
		if (pageOptimize == null) {
			return null;
		}
		QueryExecutorExtend extend = queryExecutor.getInnerModel();
		String[] paramNames = extend.getParamsName(sqlToyConfig);
		Object[] paramValues = extend.getParamsValue(sqlToyContext, sqlToyConfig);
		// sql中所有参数都为null,返回sqlId作为key
		if (paramValues == null || paramValues.length == 0) {
			return sqlToyConfig.getIdOrSql();
		}
		StringBuilder cacheKey = new StringBuilder();
		boolean isParamsNamed = true;
		if (null == paramNames || paramNames.length == 0) {
			isParamsNamed = false;
		}
		int i = 0;
		// 循环查询条件的值构造key
		for (Object value : paramValues) {
			if (i > 0) {
				cacheKey.append(",");
			}
			if (isParamsNamed) {
				cacheKey.append(paramNames[i]).append("=");
			} else {
				cacheKey.append("p_").append(i).append("=");
			}
			if (value == null) {
				cacheKey.append("null");
			} else if ((value instanceof Object[]) || value.getClass().isArray() || (value instanceof List)) {
				Object[] arrayValue = (value instanceof List) ? ((List) value).toArray()
						: CollectionUtil.convertArray(value);
				cacheKey.append("[");
				for (Object obj : arrayValue) {
					cacheKey.append((obj == null) ? "null" : obj.toString()).append(",");
				}
				cacheKey.append("]");
			} else {
				cacheKey.append(value.toString());
			}
			i++;
		}
		return cacheKey.toString();
	}

	/**
	 * @TODO 从缓存中获取具体sql相应条件的查询总记录数值
	 * @param sqlToyConfig
	 * @param pageOptimize
	 * @param conditionsKey
	 * @return
	 */
	public static Long getPageTotalCount(final SqlToyConfig sqlToyConfig, PageOptimize pageOptimize,
			String conditionsKey) {
		LinkedHashMap<String, Object[]> map = pageOptimizeCache.get(sqlToyConfig.getIdOrSql());
		// sql初次执行查询
		if (null == map) {
			return null;
		}
		Object[] values = map.get(conditionsKey);
		// 为null表示条件初次查询或已经全部过期移除
		if (null == values) {
			return null;
		}
		// 总记录数
		Long totalCount = (Long) values[1];
		// 失效时间
		long expireTime = (Long) values[0];
		long nowTime = System.currentTimeMillis();

		// 先移除(为了调整排列顺序)
		map.remove(conditionsKey);
		// 超时,返回null表示需要重新查询，并不需要定时检测
		// 1、控制总记录数量,最早的始终会排在最前面，会最先排挤出去
		// 2、每次查询时相同的条件会自动检测是否过期，过期则会重新执行
		if (nowTime >= expireTime) {
			return null;
		}
		// 重置过期时间
		values[0] = nowTime + pageOptimize.getAliveSeconds() * 1000;
		// 重新置于linkedHashMap的最后位置
		map.put(conditionsKey, values);
		return totalCount;
	}

	/**
	 * @TODO 将具体条件查询的记录数按照sql id放入缓存
	 * @param sqlToyConfig
	 * @param pageOptimize
	 * @param pageQueryKey
	 * @param totalCount
	 */
	public static void registPageTotalCount(final SqlToyConfig sqlToyConfig, PageOptimize pageOptimize,
			String pageQueryKey, Long totalCount) {
		long nowTime = System.currentTimeMillis();
		// 当前时间
		long expireTime = nowTime + pageOptimize.getAliveSeconds() * 1000;
		// 同一个分页查询sql保留的不同查询条件记录数量
		int aliveMax = pageOptimize.getAliveMax();
		// sql id
		String id = sqlToyConfig.getIdOrSql();
		LinkedHashMap<String, Object[]> map = pageOptimizeCache.get(id);
		if (null == map) {
			map = new LinkedHashMap<String, Object[]>(aliveMax);
			map.put(pageQueryKey, new Object[] { expireTime, totalCount });
			pageOptimizeCache.put(id, map);
		} else {
			map.put(pageQueryKey, new Object[] { expireTime, totalCount });
			// 长度超阀值,移除最早进入的
			while (map.size() > aliveMax) {
				map.remove(map.keySet().iterator().next());
			}

			// 剔除过期数据
			Iterator<Map.Entry<String, Object[]>> iter = map.entrySet().iterator();
			Map.Entry<String, Object[]> entry;
			while (iter.hasNext()) {
				entry = iter.next();
				// 当前时间已经大于过期时间
				if (nowTime >= ((Long) entry.getValue()[0])) {
					iter.remove();
				} else {
					break;
				}
			}
		}
	}

	/**
	 * @todo 清除掉sql对应的分页count缓存
	 * @param sqlId
	 */
	public static void remove(String sqlId) {
		pageOptimizeCache.remove(sqlId);
	}
}
