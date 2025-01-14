/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.ttddyy.observation.boot.autoconfigure;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.util.ClassUtils;

/**
 * Default implementation for {@link DataSourceNameResolver}.
 *
 * @author Tadaya Tsuyukubo
 */
public class DefaultDataSourceNameResolver implements DataSourceNameResolver {

	private static final boolean isHikariPresent = ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource",
			DataSourceObservationBeanPostProcessor.class.getClassLoader());

	@Override
	public String resolve(String beanName, DataSource dataSource) {
		if (isHikariPresent && dataSource instanceof HikariDataSource hikariDataSource) {
			if (hikariDataSource.getPoolName() != null && !hikariDataSource.getPoolName().startsWith("HikariPool-")) {
				return hikariDataSource.getPoolName();
			}
		}
		return beanName;
	}

}
