/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._

import org.apache.spark.api.python.PythonUtils
import org.apache.spark.internal.Logging
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.python.UserDefinedPythonDataSource
import org.apache.spark.util.Utils


/**
 * A manager for user-defined data sources. It is used to register and lookup data sources by
 * their short names or fully qualified names.
 */
class DataSourceManager extends Logging {
  // Lazy to avoid being invoked during Session initialization.
  // Otherwise, it goes infinite loop, session -> Python runner -> SQLConf -> session.
  private lazy val dataSourceBuilders = {
    val builders = new ConcurrentHashMap[String, UserDefinedPythonDataSource]()
    builders.putAll(DataSourceManager.initialDataSourceBuilders.asJava)
    builders
  }

  private def normalize(name: String): String = name.toLowerCase(Locale.ROOT)

  /**
   * Register a data source builder for the given provider.
   * Note that the provider name is case-insensitive.
   */
  def registerDataSource(name: String, source: UserDefinedPythonDataSource): Unit = {
    val normalizedName = normalize(name)
    val previousValue = dataSourceBuilders.put(normalizedName, source)
    if (previousValue != null) {
      logWarning(f"The data source $name replaced a previously registered data source.")
    }
  }

  /**
   * Returns a data source builder for the given provider and throw an exception if
   * it does not exist.
   */
  def lookupDataSource(name: String): UserDefinedPythonDataSource = {
    if (dataSourceExists(name)) {
      dataSourceBuilders.get(normalize(name))
    } else {
      throw QueryCompilationErrors.dataSourceDoesNotExist(name)
    }
  }

  /**
   * Checks if a data source with the specified name exists (case-insensitive).
   */
  def dataSourceExists(name: String): Boolean = {
    dataSourceBuilders.containsKey(normalize(name))
  }

  override def clone(): DataSourceManager = {
    val manager = new DataSourceManager
    dataSourceBuilders.forEach((k, v) => manager.registerDataSource(k, v))
    manager
  }
}


object DataSourceManager extends Logging {
  // Visible for testing
  private[spark] var dataSourceBuilders: Option[Map[String, UserDefinedPythonDataSource]] = None
  private lazy val shouldLoadPythonDataSources: Boolean = {
    Utils.checkCommandAvailable(PythonUtils.defaultPythonExec) &&
      // Make sure PySpark zipped files also exist.
      PythonUtils.sparkPythonPaths.forall(new File(_).exists())
  }

  private def initialDataSourceBuilders: Map[String, UserDefinedPythonDataSource] = {
    if (Utils.isTesting || shouldLoadPythonDataSources) this.synchronized {
      if (dataSourceBuilders.isEmpty) {
        val maybeResult = try {
          Some(UserDefinedPythonDataSource.lookupAllDataSourcesInPython())
        } catch {
          case e: Throwable =>
            // Even if it fails for whatever reason, we shouldn't make the whole
            // application fail.
            logWarning(
              s"Skipping the lookup of Python Data Sources due to the failure: $e")
            None
        }

        dataSourceBuilders = maybeResult.map { result =>
          result.names.zip(result.dataSources).map { case (name, dataSource) =>
            name ->
              UserDefinedPythonDataSource(PythonUtils.createPythonFunction(dataSource))
          }.toMap
        }
      }
      dataSourceBuilders.getOrElse(Map.empty)
    } else {
      Map.empty
    }
  }
}
