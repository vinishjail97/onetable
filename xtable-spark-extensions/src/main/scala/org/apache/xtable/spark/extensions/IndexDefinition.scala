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
 
package org.apache.xtable.spark.extensions

import java.util.Properties

import org.apache.spark.sql.SparkSession

import org.apache.iceberg.Table
import org.apache.iceberg.types.Type.TypeID

import scala.collection.JavaConverters._

import org.apache.xtable.index.HudiBackedIcebergSecondaryIndex

/**
 * An XTable index on one column of an Iceberg table. The definition is kept in the table
 * properties, so every session and engine that reads the table can find it:
 *
 *   - {@code xtable.index.<name>.column}: the indexed column
 *   - {@code xtable.index.<name>.option.<key>}: an option of the index, passed to the Hudi target
 *     that stores it, for example {@code xtable.hudi.target.metadata.record.index.min.filegroup.count}
 */
case class IndexDefinition(name: String, column: String, options: Map[String, String]) {
  def properties: Map[String, String] =
    Map(IndexDefinition.columnKey(name) -> column) ++ options.map { case (key, value) =>
      IndexDefinition.optionPrefix(name) + key -> value
    }
}

object IndexDefinition {
  val IndexType = "xtable"

  /**
   * Column types an index supports. The index stores values as strings, and for these types
   * Spark's cast to string and Hudi's index key are the same.
   */
  val SupportedTypes: Set[TypeID] = Set(TypeID.STRING, TypeID.INTEGER, TypeID.LONG)

  private val Prefix = "xtable.index."
  private val ColumnSuffix = ".column"
  private val ValidName = "[A-Za-z0-9_]+".r

  def columnKey(name: String): String = Prefix + name + ColumnSuffix

  def optionPrefix(name: String): String = Prefix + name + ".option."

  def validateName(name: String): Unit = name match {
    case ValidName() =>
    case _ =>
      throw new IllegalArgumentException(
        s"Invalid index name '$name': only letters, digits and underscores are allowed")
  }

  /** Reads the index definitions from the properties of a table. */
  def fromProperties(properties: java.util.Map[String, String]): Seq[IndexDefinition] = {
    val props = properties.asScala
    props.keys.toSeq
      .filter(key => key.startsWith(Prefix) && key.endsWith(ColumnSuffix))
      .map(key => key.substring(Prefix.length, key.length - ColumnSuffix.length))
      .filter(name => ValidName.pattern.matcher(name).matches())
      .sorted
      .map { name =>
        val optionPrefix = IndexDefinition.optionPrefix(name)
        val options = props.collect {
          case (key, value) if key.startsWith(optionPrefix) =>
            key.substring(optionPrefix.length) -> value
        }.toMap
        IndexDefinition(name, props(columnKey(name)), options)
      }
  }

  /** The XTable index of a table, configured with the options of all its index definitions. */
  def newIndex(
      spark: SparkSession,
      table: Table,
      definitions: Seq[IndexDefinition]): HudiBackedIcebergSecondaryIndex = {
    val properties = new Properties()
    definitions.foreach(_.options.foreach { case (key, value) =>
      properties.setProperty(key, value)
    })
    new HudiBackedIcebergSecondaryIndex(table, spark, properties)
  }
}
