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
 
package org.apache.xtable.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.apache.hudi.client.HoodieReadClient;

import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;

import org.apache.xtable.TestIcebergTable;
import org.apache.xtable.hudi.HudiTestUtil;

/**
 * Builds a Hudi backed secondary index for an Iceberg table and checks that lookups resolve to the
 * same file and row position as Iceberg's {@code _file} and {@code _pos} metadata columns.
 */
public class ITHudiBackedIcebergSecondaryIndex {
  private static final String INDEXED_COLUMN = "id";
  private static final String SECOND_INDEXED_COLUMN = "long_field";
  private static final String PARTITION_COLUMN = "level";

  @TempDir public static java.nio.file.Path tempDir;

  private static JavaSparkContext jsc;
  private static SparkSession sparkSession;

  @BeforeAll
  public static void setupOnce() {
    SparkConf sparkConf = HudiTestUtil.getSparkConf(tempDir);
    sparkSession =
        SparkSession.builder().config(HoodieReadClient.addHoodieSupport(sparkConf)).getOrCreate();
    sparkSession
        .sparkContext()
        .hadoopConfiguration()
        .set("parquet.avro.write-old-list-structure", "false");
    jsc = JavaSparkContext.fromSparkContext(sparkSession.sparkContext());
  }

  @AfterAll
  public static void teardown() {
    if (jsc != null) {
      jsc.close();
    }
    if (sparkSession != null) {
      sparkSession.close();
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = PARTITION_COLUMN)
  void syncAndLookup(String partitionField) {
    String tableName = "test_table_" + UUID.randomUUID().toString().replace("-", "_");
    try (TestIcebergTable table =
        TestIcebergTable.forStandardSchemaAndPartitioning(
            tableName, partitionField, tempDir, jsc.hadoopConfiguration())) {
      List<Record> records = new ArrayList<>(table.insertRows(100));
      Table icebergTable = table.getIcebergTable();
      HudiBackedIcebergSecondaryIndex index =
          new HudiBackedIcebergSecondaryIndex(icebergTable, sparkSession, new Properties());
      assertFalse(index.doesIndexExist(INDEXED_COLUMN));

      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertTrue(index.doesIndexExist(INDEXED_COLUMN));
      assertLookupMatchesIceberg(table, index, partitionField != null, Collections.emptyList());

      // a second batch of files is added to the index by an incremental sync
      records.addAll(table.insertRows(50));
      icebergTable.refresh();
      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertLookupMatchesIceberg(table, index, partitionField != null, Collections.emptyList());

      // updating rows rewrites the files that hold them, so the index must resolve the updated keys
      // to their new file and row position instead of the ones the previous sync recorded
      table.upsertRows(records.subList(0, 30));
      icebergTable.refresh();
      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertLookupMatchesIceberg(table, index, partitionField != null, Collections.emptyList());

      // deleting rows must drop their keys from the index, and must not strand the rows that are
      // rewritten alongside them
      List<Record> deletedRecords = new ArrayList<>(records.subList(30, 50));
      List<String> deletedKeys =
          deletedRecords.stream()
              .map(record -> record.getField(INDEXED_COLUMN).toString())
              .collect(Collectors.toList());
      table.deleteRows(deletedRecords);
      records.removeAll(deletedRecords);
      icebergTable.refresh();
      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertLookupMatchesIceberg(table, index, partitionField != null, deletedKeys);
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = PARTITION_COLUMN)
  void dropIndexAndTrackSyncedSnapshot(String partitionField) {
    String tableName = "test_table_" + UUID.randomUUID().toString().replace("-", "_");
    try (TestIcebergTable table =
        TestIcebergTable.forStandardSchemaAndPartitioning(
            tableName, partitionField, tempDir, jsc.hadoopConfiguration())) {
      table.insertRows(100);
      Table icebergTable = table.getIcebergTable();
      HudiBackedIcebergSecondaryIndex index =
          new HudiBackedIcebergSecondaryIndex(icebergTable, sparkSession, new Properties());
      assertFalse(index.getLastSyncedSourceIdentifier().isPresent());

      index.syncIndex(icebergTable, INDEXED_COLUMN);
      // the second index is added without a new snapshot
      index.syncIndex(icebergTable, SECOND_INDEXED_COLUMN);
      assertTrue(index.doesIndexExist(INDEXED_COLUMN));
      assertTrue(index.doesIndexExist(SECOND_INDEXED_COLUMN));
      assertLookupMatchesIceberg(
          table, index, SECOND_INDEXED_COLUMN, partitionField != null, Collections.emptyList());
      assertEquals(
          Optional.of(String.valueOf(icebergTable.currentSnapshot().snapshotId())),
          index.getLastSyncedSourceIdentifier());

      // a new snapshot makes the index stale until the next sync
      table.insertRows(20);
      icebergTable.refresh();
      assertNotEquals(
          Optional.of(String.valueOf(icebergTable.currentSnapshot().snapshotId())),
          index.getLastSyncedSourceIdentifier());
      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertEquals(
          Optional.of(String.valueOf(icebergTable.currentSnapshot().snapshotId())),
          index.getLastSyncedSourceIdentifier());
      // syncing one column keeps the index of the other column up to date
      assertLookupMatchesIceberg(
          table, index, SECOND_INDEXED_COLUMN, partitionField != null, Collections.emptyList());

      index.dropIndex(icebergTable, INDEXED_COLUMN);
      assertFalse(index.doesIndexExist(INDEXED_COLUMN));
      assertTrue(index.doesIndexExist(SECOND_INDEXED_COLUMN));
      assertLookupMatchesIceberg(
          table, index, SECOND_INDEXED_COLUMN, partitionField != null, Collections.emptyList());

      // a later sync must not bring the dropped index back
      table.insertRows(10);
      icebergTable.refresh();
      index.syncIndex(icebergTable, SECOND_INDEXED_COLUMN);
      assertFalse(index.doesIndexExist(INDEXED_COLUMN));
      assertLookupMatchesIceberg(
          table, index, SECOND_INDEXED_COLUMN, partitionField != null, Collections.emptyList());

      // the dropped index can be built again
      index.syncIndex(icebergTable, INDEXED_COLUMN);
      assertTrue(index.doesIndexExist(INDEXED_COLUMN));
      assertLookupMatchesIceberg(table, index, partitionField != null, Collections.emptyList());
    }
  }

  private void assertLookupMatchesIceberg(
      TestIcebergTable table,
      HudiBackedIcebergSecondaryIndex index,
      boolean partitioned,
      List<String> keysThatMustNotResolve) {
    assertLookupMatchesIceberg(table, index, INDEXED_COLUMN, partitioned, keysThatMustNotResolve);
  }

  /**
   * Looks every key currently in the Iceberg table up in the index and checks the result against
   * Iceberg's own {@code _file}, {@code _pos} and partition values. Expectations are read from the
   * table rather than from the records the test wrote, so this stays correct after rows are updated
   * or deleted, and for columns whose values repeat. {@code keysThatMustNotResolve} are looked up
   * as well and must return nothing.
   */
  private void assertLookupMatchesIceberg(
      TestIcebergTable table,
      HudiBackedIcebergSecondaryIndex index,
      String columnName,
      boolean partitioned,
      List<String> keysThatMustNotResolve) {
    Dataset<Row> icebergRows =
        sparkSession
            .read()
            .format("iceberg")
            .load(table.getBasePath())
            .where(columnName + " IS NOT NULL")
            .selectExpr(
                "CAST(" + columnName + " AS STRING) AS " + columnName,
                Index.FILE_COLUMN,
                Index.POSITION_COLUMN,
                Index.PARTITION_COLUMN);
    List<String> expectedLocations = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    icebergRows
        .collectAsList()
        .forEach(
            row -> {
              keys.add(row.getString(0));
              expectedLocations.add(
                  toLocation(
                      row.getString(0),
                      row.getString(1),
                      row.getLong(2),
                      partitioned ? row.getStruct(3) : null));
            });

    // keys that are not in the table must not produce a result
    keys.add("missing-key-1");
    keys.add("missing-key-2");
    keys.addAll(keysThatMustNotResolve);
    Dataset<Row> keysToLookUp =
        sparkSession
            .createDataset(new ArrayList<>(keys), Encoders.STRING())
            .toDF(columnName)
            .repartition(2);
    Dataset<Row> lookupResults = index.lookup(table.getIcebergTable(), keysToLookUp, columnName);
    if (partitioned) {
      // the partition column must have the type Iceberg's own _partition column has
      assertEquals(
          icebergRows.schema().apply(Index.PARTITION_COLUMN).dataType().catalogString(),
          lookupResults.schema().apply(Index.PARTITION_COLUMN).dataType().catalogString());
    }
    List<Row> lookupRows = lookupResults.collectAsList();
    Set<String> resolvedKeys =
        lookupRows.stream().map(row -> row.<String>getAs(columnName)).collect(Collectors.toSet());
    keysThatMustNotResolve.forEach(key -> assertFalse(resolvedKeys.contains(key)));
    if (!partitioned) {
      lookupRows.forEach(row -> assertNull(row.getAs(Index.PARTITION_COLUMN)));
    }

    List<String> actualLocations =
        lookupRows.stream()
            .map(
                row ->
                    toLocation(
                        row.getAs(columnName),
                        row.getAs(Index.FILE_COLUMN),
                        row.getAs(Index.POSITION_COLUMN),
                        row.getAs(Index.PARTITION_COLUMN)))
            .collect(Collectors.toList());
    Collections.sort(expectedLocations);
    Collections.sort(actualLocations);
    assertEquals(expectedLocations, actualLocations);
  }

  private static String toLocation(String key, String file, long position, Row partition) {
    return key + "|" + new Path(file).toUri().getPath() + "|" + position + "|" + partition;
  }
}
