/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.spark.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import scala.collection.JavaConverters;

public class TestIndexDefinition {

  @Test
  void readsDefinitionsFromTableProperties() {
    Map<String, String> properties = new HashMap<>();
    properties.put("format", "iceberg/parquet");
    properties.put("xtable.index.email_idx.column", "email");
    properties.put(
        "xtable.index.email_idx.option.xtable.hudi.target.metadata.record.index.min.filegroup.count",
        "8");
    properties.put("xtable.index.id_idx.column", "id");
    // an option without a column is not an index
    properties.put("xtable.index.orphan.option.k", "v");

    List<IndexDefinition> definitions =
        JavaConverters.seqAsJavaList(IndexDefinition.fromProperties(properties));

    Map<String, String> emailOptions = new HashMap<>();
    emailOptions.put("xtable.hudi.target.metadata.record.index.min.filegroup.count", "8");
    assertEquals(
        Arrays.asList(
            new IndexDefinition("email_idx", "email", toScala(emailOptions)),
            new IndexDefinition("id_idx", "id", toScala(new HashMap<>()))),
        definitions);
  }

  @Test
  void writesTheSamePropertiesItReads() {
    Map<String, String> options = new HashMap<>();
    options.put("k", "v");
    IndexDefinition definition = new IndexDefinition("email_idx", "email", toScala(options));
    Map<String, String> properties =
        new HashMap<>(JavaConverters.mapAsJavaMap(definition.properties()));
    assertEquals(
        Arrays.asList(definition),
        JavaConverters.seqAsJavaList(IndexDefinition.fromProperties(properties)));
  }

  @Test
  void rejectsNamesThatBreakThePropertyKeys() {
    IndexDefinition.validateName("email_idx_2");
    assertThrows(IllegalArgumentException.class, () -> IndexDefinition.validateName("email.idx"));
    assertThrows(IllegalArgumentException.class, () -> IndexDefinition.validateName("email idx"));
  }

  private static scala.collection.immutable.Map<String, String> toScala(Map<String, String> map) {
    return scala.collection.immutable.Map$.MODULE$
        .<String, String>empty()
        .$plus$plus(JavaConverters.mapAsScalaMap(map));
  }
}
