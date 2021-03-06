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

package org.apache.spark.sql.connector.catalog

import java.util
import java.util.Collections

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{NamedRelation, NoSuchDatabaseException, NoSuchNamespaceException, NoSuchTableException, UnresolvedV2Relation}
import org.apache.spark.sql.catalyst.plans.logical.AlterTable
import org.apache.spark.sql.connector.catalog.TableChange._
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.types.{ArrayType, MapType, StructField, StructType}

private[sql] object CatalogV2Util {
  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

  /**
   * Apply properties changes to a map and return the result.
   */
  def applyNamespaceChanges(
      properties: Map[String, String],
      changes: Seq[NamespaceChange]): Map[String, String] = {
    applyNamespaceChanges(properties.asJava, changes).asScala.toMap
  }

  /**
   * Apply properties changes to a Java map and return the result.
   */
  def applyNamespaceChanges(
      properties: util.Map[String, String],
      changes: Seq[NamespaceChange]): util.Map[String, String] = {
    val newProperties = new util.HashMap[String, String](properties)

    changes.foreach {
      case set: NamespaceChange.SetProperty =>
        newProperties.put(set.property, set.value)

      case unset: NamespaceChange.RemoveProperty =>
        newProperties.remove(unset.property)

      case _ =>
      // ignore non-property changes
    }

    Collections.unmodifiableMap(newProperties)
  }

  /**
   * Apply properties changes to a map and return the result.
   */
  def applyPropertiesChanges(
      properties: Map[String, String],
      changes: Seq[TableChange]): Map[String, String] = {
    applyPropertiesChanges(properties.asJava, changes).asScala.toMap
  }

  /**
   * Apply properties changes to a Java map and return the result.
   */
  def applyPropertiesChanges(
      properties: util.Map[String, String],
      changes: Seq[TableChange]): util.Map[String, String] = {
    val newProperties = new util.HashMap[String, String](properties)

    changes.foreach {
      case set: SetProperty =>
        newProperties.put(set.property, set.value)

      case unset: RemoveProperty =>
        newProperties.remove(unset.property)

      case _ =>
      // ignore non-property changes
    }

    Collections.unmodifiableMap(newProperties)
  }

  /**
   * Apply schema changes to a schema and return the result.
   */
  def applySchemaChanges(schema: StructType, changes: Seq[TableChange]): StructType = {
    changes.foldLeft(schema) { (schema, change) =>
      change match {
        case add: AddColumn =>
          add.fieldNames match {
            case Array(name) =>
              val newField = StructField(name, add.dataType, nullable = add.isNullable)
              Option(add.comment) match {
                case Some(comment) =>
                  schema.add(newField.withComment(comment))
                case _ =>
                  schema.add(newField)
              }

            case names =>
              replace(schema, names.init, parent => parent.dataType match {
                case parentType: StructType =>
                  val field = StructField(names.last, add.dataType, nullable = add.isNullable)
                  val newParentType = Option(add.comment) match {
                    case Some(comment) =>
                      parentType.add(field.withComment(comment))
                    case None =>
                      parentType.add(field)
                  }

                  Some(StructField(parent.name, newParentType, parent.nullable, parent.metadata))

                case _ =>
                  throw new IllegalArgumentException(s"Not a struct: ${names.init.last}")
              })
          }

        case rename: RenameColumn =>
          replace(schema, rename.fieldNames, field =>
            Some(StructField(rename.newName, field.dataType, field.nullable, field.metadata)))

        case update: UpdateColumnType =>
          replace(schema, update.fieldNames, field => {
            if (!update.isNullable && field.nullable) {
              throw new IllegalArgumentException(
                s"Cannot change optional column to required: $field.name")
            }
            Some(StructField(field.name, update.newDataType, update.isNullable, field.metadata))
          })

        case update: UpdateColumnComment =>
          replace(schema, update.fieldNames, field =>
            Some(field.withComment(update.newComment)))

        case delete: DeleteColumn =>
          replace(schema, delete.fieldNames, _ => None)

        case _ =>
          // ignore non-schema changes
          schema
      }
    }
  }

  private def replace(
      struct: StructType,
      fieldNames: Seq[String],
      update: StructField => Option[StructField]): StructType = {

    val pos = struct.getFieldIndex(fieldNames.head)
        .getOrElse(throw new IllegalArgumentException(s"Cannot find field: ${fieldNames.head}"))
    val field = struct.fields(pos)
    val replacement: Option[StructField] = (fieldNames.tail, field.dataType) match {
      case (Seq(), _) =>
        update(field)

      case (names, struct: StructType) =>
        val updatedType: StructType = replace(struct, names, update)
        Some(StructField(field.name, updatedType, field.nullable, field.metadata))

      case (Seq("key"), map @ MapType(keyType, _, _)) =>
        val updated = update(StructField("key", keyType, nullable = false))
            .getOrElse(throw new IllegalArgumentException(s"Cannot delete map key"))
        Some(field.copy(dataType = map.copy(keyType = updated.dataType)))

      case (Seq("key", names @ _*), map @ MapType(keyStruct: StructType, _, _)) =>
        Some(field.copy(dataType = map.copy(keyType = replace(keyStruct, names, update))))

      case (Seq("value"), map @ MapType(_, mapValueType, isNullable)) =>
        val updated = update(StructField("value", mapValueType, nullable = isNullable))
            .getOrElse(throw new IllegalArgumentException(s"Cannot delete map value"))
        Some(field.copy(dataType = map.copy(
          valueType = updated.dataType,
          valueContainsNull = updated.nullable)))

      case (Seq("value", names @ _*), map @ MapType(_, valueStruct: StructType, _)) =>
        Some(field.copy(dataType = map.copy(valueType = replace(valueStruct, names, update))))

      case (Seq("element"), array @ ArrayType(elementType, isNullable)) =>
        val updated = update(StructField("element", elementType, nullable = isNullable))
            .getOrElse(throw new IllegalArgumentException(s"Cannot delete array element"))
        Some(field.copy(dataType = array.copy(
          elementType = updated.dataType,
          containsNull = updated.nullable)))

      case (Seq("element", names @ _*), array @ ArrayType(elementStruct: StructType, _)) =>
        Some(field.copy(dataType = array.copy(elementType = replace(elementStruct, names, update))))

      case (names, dataType) =>
        throw new IllegalArgumentException(
          s"Cannot find field: ${names.head} in ${dataType.simpleString}")
    }

    val newFields = struct.fields.zipWithIndex.flatMap {
      case (_, index) if pos == index =>
        replacement
      case (other, _) =>
        Some(other)
    }

    new StructType(newFields)
  }

  def loadRelation(catalog: CatalogPlugin, ident: Identifier): Option[NamedRelation] = {
    loadTable(catalog, ident).map(DataSourceV2Relation.create)
  }

  def loadTable(catalog: CatalogPlugin, ident: Identifier): Option[Table] =
    try {
      Option(catalog.asTableCatalog.loadTable(ident))
    } catch {
      case _: NoSuchTableException => None
      case _: NoSuchDatabaseException => None
      case _: NoSuchNamespaceException => None
    }

  def isSessionCatalog(catalog: CatalogPlugin): Boolean = {
    catalog.name().equalsIgnoreCase(CatalogManager.SESSION_CATALOG_NAME)
  }

  def convertTableProperties(
      properties: Map[String, String],
      options: Map[String, String],
      location: Option[String],
      comment: Option[String],
      provider: String): Map[String, String] = {
    if (options.contains("path") && location.isDefined) {
      throw new AnalysisException(
        "LOCATION and 'path' in OPTIONS are both used to indicate the custom table path, " +
          "you can only specify one of them.")
    }

    if ((options.contains("comment") || properties.contains("comment"))
      && comment.isDefined) {
      throw new AnalysisException(
        "COMMENT and option/property 'comment' are both used to set the table comment, you can " +
          "only specify one of them.")
    }

    if (options.contains("provider") || properties.contains("provider")) {
      throw new AnalysisException(
        "USING and option/property 'provider' are both used to set the provider implementation, " +
          "you can only specify one of them.")
    }

    val filteredOptions = options.filterKeys(_ != "path")

    // create table properties from TBLPROPERTIES and OPTIONS clauses
    val tableProperties = new mutable.HashMap[String, String]()
    tableProperties ++= properties
    tableProperties ++= filteredOptions

    // convert USING, LOCATION, and COMMENT clauses to table properties
    tableProperties += ("provider" -> provider)
    comment.map(text => tableProperties += ("comment" -> text))
    location.orElse(options.get("path")).map(loc => tableProperties += ("location" -> loc))

    tableProperties.toMap
  }

  def createAlterTable(
      originalNameParts: Seq[String],
      catalog: CatalogPlugin,
      tableName: Seq[String],
      changes: Seq[TableChange]): AlterTable = {
    val tableCatalog = catalog.asTableCatalog
    val ident = tableName.asIdentifier
    val unresolved = UnresolvedV2Relation(originalNameParts, tableCatalog, ident)
    AlterTable(tableCatalog, ident, unresolved, changes)
  }
}
