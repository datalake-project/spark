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

package org.apache.spark.sql.connector.catalog;

import org.apache.spark.annotation.Experimental;
import org.apache.spark.sql.connector.write.BatchWrite;
import org.apache.spark.sql.connector.write.WriteBuilder;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

/**
 * An internal base interface of mix-in interfaces for writable {@link Table}. This adds
 * {@link #newWriteBuilder(CaseInsensitiveStringMap)} that is used to create a write
 * for batch or streaming.
 */
@Experimental
public interface SupportsWrite extends Table {

  /**
   * Returns a {@link WriteBuilder} which can be used to create {@link BatchWrite}. Spark will call
   * this method to configure each data source write.
   */
  WriteBuilder newWriteBuilder(CaseInsensitiveStringMap options);
}
