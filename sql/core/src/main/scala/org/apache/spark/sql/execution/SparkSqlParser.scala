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

package org.apache.spark.sql.execution

import java.util.Locale

import scala.collection.JavaConverters._

import org.antlr.v4.runtime.{ParserRuleContext, Token}
import org.antlr.v4.runtime.tree.TerminalNode

import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser._
import org.apache.spark.sql.catalyst.parser.SqlBaseParser._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.command._
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.internal.{HiveSerDe, SQLConf, VariableSubstitution}
import org.apache.spark.sql.types.StructType

/**
 * Concrete parser for Spark SQL statements.
 */
class SparkSqlParser(conf: SQLConf) extends AbstractSqlParser {
  val astBuilder = new SparkSqlAstBuilder(conf)

  private val substitutor = new VariableSubstitution(conf)

  protected override def parse[T](command: String)(toResult: SqlBaseParser => T): T = {
    super.parse(substitutor.substitute(command))(toResult)
  }
}

/**
 * Builder that converts an ANTLR ParseTree into a LogicalPlan/Expression/TableIdentifier.
 */
class SparkSqlAstBuilder(conf: SQLConf) extends AstBuilder(conf) {
  import org.apache.spark.sql.catalyst.parser.ParserUtils._

  /**
   * Create a [[SetCommand]] logical plan.
   *
   * Note that we assume that everything after the SET keyword is assumed to be a part of the
   * key-value pair. The split between key and value is made by searching for the first `=`
   * character in the raw string.
   */
  override def visitSetConfiguration(ctx: SetConfigurationContext): LogicalPlan = withOrigin(ctx) {
    // Construct the command.
    val raw = remainder(ctx.SET.getSymbol)
    val keyValueSeparatorIndex = raw.indexOf('=')
    if (keyValueSeparatorIndex >= 0) {
      val key = raw.substring(0, keyValueSeparatorIndex).trim
      val value = raw.substring(keyValueSeparatorIndex + 1).trim
      SetCommand(Some(key -> Option(value)))
    } else if (raw.nonEmpty) {
      SetCommand(Some(raw.trim -> None))
    } else {
      SetCommand(None)
    }
  }

  /**
   * Create a [[ResetCommand]] logical plan.
   * Example SQL :
   * {{{
   *   RESET;
   * }}}
   */
  override def visitResetConfiguration(
      ctx: ResetConfigurationContext): LogicalPlan = withOrigin(ctx) {
    ResetCommand
  }

  /**
   * Create an [[AnalyzeTableCommand]] command, or an [[AnalyzePartitionCommand]]
   * or an [[AnalyzeColumnCommand]] command.
   * Example SQL for analyzing a table or a set of partitions :
   * {{{
   *   ANALYZE TABLE [db_name.]tablename [PARTITION (partcol1[=val1], partcol2[=val2], ...)]
   *   COMPUTE STATISTICS [NOSCAN];
   * }}}
   *
   * Example SQL for analyzing columns :
   * {{{
   *   ANALYZE TABLE [db_name.]tablename COMPUTE STATISTICS FOR COLUMNS column1, column2;
   * }}}
   *
   * Example SQL for analyzing all columns of a table:
   * {{{
   *   ANALYZE TABLE [db_name.]tablename COMPUTE STATISTICS FOR ALL COLUMNS;
   * }}}
   */
  override def visitAnalyze(ctx: AnalyzeContext): LogicalPlan = withOrigin(ctx) {
    def checkPartitionSpec(): Unit = {
      if (ctx.partitionSpec != null) {
        logWarning("Partition specification is ignored when collecting column statistics: " +
          ctx.partitionSpec.getText)
      }
    }
    if (ctx.identifier != null &&
        ctx.identifier.getText.toLowerCase(Locale.ROOT) != "noscan") {
      throw new ParseException(s"Expected `NOSCAN` instead of `${ctx.identifier.getText}`", ctx)
    }

    val table = visitTableIdentifier(ctx.tableIdentifier)
    if (ctx.ALL() != null) {
      checkPartitionSpec()
      AnalyzeColumnCommand(table, None, allColumns = true)
    } else if (ctx.identifierSeq() == null) {
      if (ctx.partitionSpec != null) {
        AnalyzePartitionCommand(table, visitPartitionSpec(ctx.partitionSpec),
          noscan = ctx.identifier != null)
      } else {
        AnalyzeTableCommand(table, noscan = ctx.identifier != null)
      }
    } else {
      checkPartitionSpec()
      AnalyzeColumnCommand(table,
        Option(visitIdentifierSeq(ctx.identifierSeq())), allColumns = false)
    }
  }

  /**
   * Create a [[SetDatabaseCommand]] logical plan.
   */
  override def visitUse(ctx: UseContext): LogicalPlan = withOrigin(ctx) {
    SetDatabaseCommand(ctx.db.getText)
  }

  /**
   * Create a [[ShowTablesCommand]] logical plan.
   * Example SQL :
   * {{{
   *   SHOW TABLES [(IN|FROM) database_name] [[LIKE] 'identifier_with_wildcards'];
   * }}}
   */
  override def visitShowTables(ctx: ShowTablesContext): LogicalPlan = withOrigin(ctx) {
    ShowTablesCommand(
      Option(ctx.db).map(_.getText),
      Option(ctx.pattern).map(string),
      isExtended = false,
      partitionSpec = None)
  }

  /**
   * Create a [[ShowTablesCommand]] logical plan.
   * Example SQL :
   * {{{
   *   SHOW TABLE EXTENDED [(IN|FROM) database_name] LIKE 'identifier_with_wildcards'
   *   [PARTITION(partition_spec)];
   * }}}
   */
  override def visitShowTable(ctx: ShowTableContext): LogicalPlan = withOrigin(ctx) {
    val partitionSpec = Option(ctx.partitionSpec).map(visitNonOptionalPartitionSpec)
    ShowTablesCommand(
      Option(ctx.db).map(_.getText),
      Option(ctx.pattern).map(string),
      isExtended = true,
      partitionSpec = partitionSpec)
  }

  /**
   * Create a [[ShowDatabasesCommand]] logical plan.
   * Example SQL:
   * {{{
   *   SHOW (DATABASES|SCHEMAS) [LIKE 'identifier_with_wildcards'];
   * }}}
   */
  override def visitShowDatabases(ctx: ShowDatabasesContext): LogicalPlan = withOrigin(ctx) {
    ShowDatabasesCommand(Option(ctx.pattern).map(string))
  }

  /**
   * A command for users to list the properties for a table. If propertyKey is specified, the value
   * for the propertyKey is returned. If propertyKey is not specified, all the keys and their
   * corresponding values are returned.
   * The syntax of using this command in SQL is:
   * {{{
   *   SHOW TBLPROPERTIES table_name[('propertyKey')];
   * }}}
   */
  override def visitShowTblProperties(
      ctx: ShowTblPropertiesContext): LogicalPlan = withOrigin(ctx) {
    ShowTablePropertiesCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      Option(ctx.key).map(visitTablePropertyKey))
  }

  /**
   * A command for users to list the column names for a table.
   * This function creates a [[ShowColumnsCommand]] logical plan.
   *
   * The syntax of using this command in SQL is:
   * {{{
   *   SHOW COLUMNS (FROM | IN) table_identifier [(FROM | IN) database];
   * }}}
   */
  override def visitShowColumns(ctx: ShowColumnsContext): LogicalPlan = withOrigin(ctx) {
    ShowColumnsCommand(Option(ctx.db).map(_.getText), visitTableIdentifier(ctx.tableIdentifier))
  }

  /**
   * A command for users to list the partition names of a table. If partition spec is specified,
   * partitions that match the spec are returned. Otherwise an empty result set is returned.
   *
   * This function creates a [[ShowPartitionsCommand]] logical plan
   *
   * The syntax of using this command in SQL is:
   * {{{
   *   SHOW PARTITIONS table_identifier [partition_spec];
   * }}}
   */
  override def visitShowPartitions(ctx: ShowPartitionsContext): LogicalPlan = withOrigin(ctx) {
    val table = visitTableIdentifier(ctx.tableIdentifier)
    val partitionKeys = Option(ctx.partitionSpec).map(visitNonOptionalPartitionSpec)
    ShowPartitionsCommand(table, partitionKeys)
  }

  /**
   * Creates a [[ShowCreateTableCommand]]
   */
  override def visitShowCreateTable(ctx: ShowCreateTableContext): LogicalPlan = withOrigin(ctx) {
    val table = visitTableIdentifier(ctx.tableIdentifier())
    ShowCreateTableCommand(table)
  }

  /**
   * Create a [[RefreshTable]] logical plan.
   */
  override def visitRefreshTable(ctx: RefreshTableContext): LogicalPlan = withOrigin(ctx) {
    RefreshTable(visitTableIdentifier(ctx.tableIdentifier))
  }

  /**
   * Create a [[RefreshResource]] logical plan.
   */
  override def visitRefreshResource(ctx: RefreshResourceContext): LogicalPlan = withOrigin(ctx) {
    val path = if (ctx.STRING != null) string(ctx.STRING) else extractUnquotedResourcePath(ctx)
    RefreshResource(path)
  }

  private def extractUnquotedResourcePath(ctx: RefreshResourceContext): String = withOrigin(ctx) {
    val unquotedPath = remainder(ctx.REFRESH.getSymbol).trim
    validate(
      unquotedPath != null && !unquotedPath.isEmpty,
      "Resource paths cannot be empty in REFRESH statements. Use / to match everything",
      ctx)
    val forbiddenSymbols = Seq(" ", "\n", "\r", "\t")
    validate(
      !forbiddenSymbols.exists(unquotedPath.contains(_)),
      "REFRESH statements cannot contain ' ', '\\n', '\\r', '\\t' inside unquoted resource paths",
      ctx)
    unquotedPath
  }

  /**
   * Create a [[CacheTableCommand]] logical plan.
   */
  override def visitCacheTable(ctx: CacheTableContext): LogicalPlan = withOrigin(ctx) {
    val query = Option(ctx.query).map(plan)
    val tableIdent = visitTableIdentifier(ctx.tableIdentifier)
    if (query.isDefined && tableIdent.database.isDefined) {
      val database = tableIdent.database.get
      throw new ParseException(s"It is not allowed to add database prefix `$database` to " +
        s"the table name in CACHE TABLE AS SELECT", ctx)
    }
    CacheTableCommand(tableIdent, query, ctx.LAZY != null)
  }

  /**
   * Create an [[UncacheTableCommand]] logical plan.
   */
  override def visitUncacheTable(ctx: UncacheTableContext): LogicalPlan = withOrigin(ctx) {
    UncacheTableCommand(visitTableIdentifier(ctx.tableIdentifier), ctx.EXISTS != null)
  }

  /**
   * Create a [[ClearCacheCommand]] logical plan.
   */
  override def visitClearCache(ctx: ClearCacheContext): LogicalPlan = withOrigin(ctx) {
    ClearCacheCommand()
  }

  /**
   * Create an [[ExplainCommand]] logical plan.
   * The syntax of using this command in SQL is:
   * {{{
   *   EXPLAIN (EXTENDED | CODEGEN) SELECT * FROM ...
   * }}}
   */
  override def visitExplain(ctx: ExplainContext): LogicalPlan = withOrigin(ctx) {
    if (ctx.FORMATTED != null) {
      operationNotAllowed("EXPLAIN FORMATTED", ctx)
    }
    if (ctx.LOGICAL != null) {
      operationNotAllowed("EXPLAIN LOGICAL", ctx)
    }

    val statement = plan(ctx.statement)
    if (statement == null) {
      null  // This is enough since ParseException will raise later.
    } else if (isExplainableStatement(statement)) {
      ExplainCommand(
        logicalPlan = statement,
        extended = ctx.EXTENDED != null,
        codegen = ctx.CODEGEN != null,
        cost = ctx.COST != null)
    } else {
      ExplainCommand(OneRowRelation())
    }
  }

  /**
   * Determine if a plan should be explained at all.
   */
  protected def isExplainableStatement(plan: LogicalPlan): Boolean = plan match {
    case _: DescribeTableCommand => false
    case _ => true
  }

  /**
   * Create a [[DescribeColumnCommand]] or [[DescribeTableCommand]] logical commands.
   */
  override def visitDescribeTable(ctx: DescribeTableContext): LogicalPlan = withOrigin(ctx) {
    val isExtended = ctx.EXTENDED != null || ctx.FORMATTED != null
    if (ctx.describeColName != null) {
      if (ctx.partitionSpec != null) {
        throw new ParseException("DESC TABLE COLUMN for a specific partition is not supported", ctx)
      } else {
        DescribeColumnCommand(
          visitTableIdentifier(ctx.tableIdentifier),
          ctx.describeColName.nameParts.asScala.map(_.getText),
          isExtended)
      }
    } else {
      val partitionSpec = if (ctx.partitionSpec != null) {
        // According to the syntax, visitPartitionSpec returns `Map[String, Option[String]]`.
        visitPartitionSpec(ctx.partitionSpec).map {
          case (key, Some(value)) => key -> value
          case (key, _) =>
            throw new ParseException(s"PARTITION specification is incomplete: `$key`", ctx)
        }
      } else {
        Map.empty[String, String]
      }
      DescribeTableCommand(
        visitTableIdentifier(ctx.tableIdentifier),
        partitionSpec,
        isExtended)
    }
  }

  /**
   * Converts a multi-part identifier to a TableIdentifier.
   *
   * If the multi-part identifier has too many parts, this will throw a ParseException.
   */
  def tableIdentifier(
      multipart: Seq[String],
      command: String,
      ctx: ParserRuleContext): TableIdentifier = {
    multipart match {
      case Seq(tableName) =>
        TableIdentifier(tableName)
      case Seq(database, tableName) =>
        TableIdentifier(tableName, Some(database))
      case _ =>
        operationNotAllowed(s"$command does not support multi-part identifiers", ctx)
    }
  }

  /**
   * Create a table, returning a [[CreateTable]] logical plan.
   *
   * This is used to produce CreateTempViewUsing from CREATE TEMPORARY TABLE.
   *
   * TODO: Remove this. It is used because CreateTempViewUsing is not a Catalyst plan.
   * Either move CreateTempViewUsing into catalyst as a parsed logical plan, or remove it because
   * it is deprecated.
   */
  override def visitCreateTable(ctx: CreateTableContext): LogicalPlan = withOrigin(ctx) {
    val (ident, temp, ifNotExists, external) = visitCreateTableHeader(ctx.createTableHeader)

    if (!temp || ctx.query != null) {
      super.visitCreateTable(ctx)
    } else {
      if (external) {
        operationNotAllowed("CREATE EXTERNAL TABLE ... USING", ctx)
      }

      checkDuplicateClauses(ctx.TBLPROPERTIES, "TBLPROPERTIES", ctx)
      checkDuplicateClauses(ctx.OPTIONS, "OPTIONS", ctx)
      checkDuplicateClauses(ctx.PARTITIONED, "PARTITIONED BY", ctx)
      checkDuplicateClauses(ctx.COMMENT, "COMMENT", ctx)
      checkDuplicateClauses(ctx.bucketSpec(), "CLUSTERED BY", ctx)
      checkDuplicateClauses(ctx.locationSpec, "LOCATION", ctx)

      if (ifNotExists) {
        // Unlike CREATE TEMPORARY VIEW USING, CREATE TEMPORARY TABLE USING does not support
        // IF NOT EXISTS. Users are not allowed to replace the existing temp table.
        operationNotAllowed("CREATE TEMPORARY TABLE IF NOT EXISTS", ctx)
      }

      val options = Option(ctx.options).map(visitPropertyKeyValues).getOrElse(Map.empty)
      val provider = ctx.tableProvider.qualifiedName.getText
      val schema = Option(ctx.colTypeList()).map(createSchema)

      logWarning(s"CREATE TEMPORARY TABLE ... USING ... is deprecated, please use " +
          "CREATE TEMPORARY VIEW ... USING ... instead")

      val table = tableIdentifier(ident, "CREATE TEMPORARY VIEW", ctx)
      CreateTempViewUsing(table, schema, replace = false, global = false, provider, options)
    }
  }

  /**
   * Creates a [[CreateTempViewUsing]] logical plan.
   */
  override def visitCreateTempViewUsing(
      ctx: CreateTempViewUsingContext): LogicalPlan = withOrigin(ctx) {
    CreateTempViewUsing(
      tableIdent = visitTableIdentifier(ctx.tableIdentifier()),
      userSpecifiedSchema = Option(ctx.colTypeList()).map(createSchema),
      replace = ctx.REPLACE != null,
      global = ctx.GLOBAL != null,
      provider = ctx.tableProvider.qualifiedName.getText,
      options = Option(ctx.tablePropertyList).map(visitPropertyKeyValues).getOrElse(Map.empty))
  }

  /**
   * Create a [[LoadDataCommand]] command.
   *
   * For example:
   * {{{
   *   LOAD DATA [LOCAL] INPATH 'filepath' [OVERWRITE] INTO TABLE tablename
   *   [PARTITION (partcol1=val1, partcol2=val2 ...)]
   * }}}
   */
  override def visitLoadData(ctx: LoadDataContext): LogicalPlan = withOrigin(ctx) {
    LoadDataCommand(
      table = visitTableIdentifier(ctx.tableIdentifier),
      path = string(ctx.path),
      isLocal = ctx.LOCAL != null,
      isOverwrite = ctx.OVERWRITE != null,
      partition = Option(ctx.partitionSpec).map(visitNonOptionalPartitionSpec)
    )
  }

  /**
   * Create a [[TruncateTableCommand]] command.
   *
   * For example:
   * {{{
   *   TRUNCATE TABLE tablename [PARTITION (partcol1=val1, partcol2=val2 ...)]
   * }}}
   */
  override def visitTruncateTable(ctx: TruncateTableContext): LogicalPlan = withOrigin(ctx) {
    TruncateTableCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      Option(ctx.partitionSpec).map(visitNonOptionalPartitionSpec))
  }

  /**
   * Create a [[AlterTableRecoverPartitionsCommand]] command.
   *
   * For example:
   * {{{
   *   MSCK REPAIR TABLE tablename
   * }}}
   */
  override def visitRepairTable(ctx: RepairTableContext): LogicalPlan = withOrigin(ctx) {
    AlterTableRecoverPartitionsCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      "MSCK REPAIR TABLE")
  }

  /**
   * Create a [[CreateDatabaseCommand]] command.
   *
   * For example:
   * {{{
   *   CREATE DATABASE [IF NOT EXISTS] database_name [COMMENT database_comment]
   *    [LOCATION path] [WITH DBPROPERTIES (key1=val1, key2=val2, ...)]
   * }}}
   */
  override def visitCreateDatabase(ctx: CreateDatabaseContext): LogicalPlan = withOrigin(ctx) {
    CreateDatabaseCommand(
      ctx.identifier.getText,
      ctx.EXISTS != null,
      Option(ctx.locationSpec).map(visitLocationSpec),
      Option(ctx.comment).map(string),
      Option(ctx.tablePropertyList).map(visitPropertyKeyValues).getOrElse(Map.empty))
  }

  /**
   * Create an [[AlterDatabasePropertiesCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER (DATABASE|SCHEMA) database SET DBPROPERTIES (property_name=property_value, ...);
   * }}}
   */
  override def visitSetDatabaseProperties(
      ctx: SetDatabasePropertiesContext): LogicalPlan = withOrigin(ctx) {
    AlterDatabasePropertiesCommand(
      ctx.identifier.getText,
      visitPropertyKeyValues(ctx.tablePropertyList))
  }

  /**
   * Create a [[DropDatabaseCommand]] command.
   *
   * For example:
   * {{{
   *   DROP (DATABASE|SCHEMA) [IF EXISTS] database [RESTRICT|CASCADE];
   * }}}
   */
  override def visitDropDatabase(ctx: DropDatabaseContext): LogicalPlan = withOrigin(ctx) {
    DropDatabaseCommand(ctx.identifier.getText, ctx.EXISTS != null, ctx.CASCADE != null)
  }

  /**
   * Create a [[DescribeDatabaseCommand]] command.
   *
   * For example:
   * {{{
   *   DESCRIBE DATABASE [EXTENDED] database;
   * }}}
   */
  override def visitDescribeDatabase(ctx: DescribeDatabaseContext): LogicalPlan = withOrigin(ctx) {
    DescribeDatabaseCommand(ctx.identifier.getText, ctx.EXTENDED != null)
  }

  /**
   * Create a plan for a DESCRIBE FUNCTION command.
   */
  override def visitDescribeFunction(ctx: DescribeFunctionContext): LogicalPlan = withOrigin(ctx) {
    import ctx._
    val functionName =
      if (describeFuncName.STRING() != null) {
        FunctionIdentifier(string(describeFuncName.STRING()), database = None)
      } else if (describeFuncName.qualifiedName() != null) {
        visitFunctionName(describeFuncName.qualifiedName)
      } else {
        FunctionIdentifier(describeFuncName.getText, database = None)
      }
    DescribeFunctionCommand(functionName, EXTENDED != null)
  }

  /**
   * Create a plan for a SHOW FUNCTIONS command.
   */
  override def visitShowFunctions(ctx: ShowFunctionsContext): LogicalPlan = withOrigin(ctx) {
    import ctx._
    val (user, system) = Option(ctx.identifier).map(_.getText.toLowerCase(Locale.ROOT)) match {
      case None | Some("all") => (true, true)
      case Some("system") => (false, true)
      case Some("user") => (true, false)
      case Some(x) => throw new ParseException(s"SHOW $x FUNCTIONS not supported", ctx)
    }

    val (db, pat) = if (qualifiedName != null) {
      val name = visitFunctionName(qualifiedName)
      (name.database, Some(name.funcName))
    } else if (pattern != null) {
      (None, Some(string(pattern)))
    } else {
      (None, None)
    }

    ShowFunctionsCommand(db, pat, user, system)
  }

  /**
   * Create a [[CreateFunctionCommand]] command.
   *
   * For example:
   * {{{
   *   CREATE [OR REPLACE] [TEMPORARY] FUNCTION [IF NOT EXISTS] [db_name.]function_name
   *   AS class_name [USING JAR|FILE|ARCHIVE 'file_uri' [, JAR|FILE|ARCHIVE 'file_uri']];
   * }}}
   */
  override def visitCreateFunction(ctx: CreateFunctionContext): LogicalPlan = withOrigin(ctx) {
    val resources = ctx.resource.asScala.map { resource =>
      val resourceType = resource.identifier.getText.toLowerCase(Locale.ROOT)
      resourceType match {
        case "jar" | "file" | "archive" =>
          FunctionResource(FunctionResourceType.fromString(resourceType), string(resource.STRING))
        case other =>
          operationNotAllowed(s"CREATE FUNCTION with resource type '$resourceType'", ctx)
      }
    }

    // Extract database, name & alias.
    val functionIdentifier = visitFunctionName(ctx.qualifiedName)
    CreateFunctionCommand(
      functionIdentifier.database,
      functionIdentifier.funcName,
      string(ctx.className),
      resources,
      ctx.TEMPORARY != null,
      ctx.EXISTS != null,
      ctx.REPLACE != null)
  }

  /**
   * Create a [[DropFunctionCommand]] command.
   *
   * For example:
   * {{{
   *   DROP [TEMPORARY] FUNCTION [IF EXISTS] function;
   * }}}
   */
  override def visitDropFunction(ctx: DropFunctionContext): LogicalPlan = withOrigin(ctx) {
    val functionIdentifier = visitFunctionName(ctx.qualifiedName)
    DropFunctionCommand(
      functionIdentifier.database,
      functionIdentifier.funcName,
      ctx.EXISTS != null,
      ctx.TEMPORARY != null)
  }

  /**
   * Create a [[AlterTableRenameCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER TABLE table1 RENAME TO table2;
   *   ALTER VIEW view1 RENAME TO view2;
   * }}}
   */
  override def visitRenameTable(ctx: RenameTableContext): LogicalPlan = withOrigin(ctx) {
    AlterTableRenameCommand(
      visitTableIdentifier(ctx.from),
      visitTableIdentifier(ctx.to),
      ctx.VIEW != null)
  }

  /**
   * Create an [[AlterTableSerDePropertiesCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER TABLE table [PARTITION spec] SET SERDE serde_name [WITH SERDEPROPERTIES props];
   *   ALTER TABLE table [PARTITION spec] SET SERDEPROPERTIES serde_properties;
   * }}}
   */
  override def visitSetTableSerDe(ctx: SetTableSerDeContext): LogicalPlan = withOrigin(ctx) {
    AlterTableSerDePropertiesCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      Option(ctx.STRING).map(string),
      Option(ctx.tablePropertyList).map(visitPropertyKeyValues),
      // TODO a partition spec is allowed to have optional values. This is currently violated.
      Option(ctx.partitionSpec).map(visitNonOptionalPartitionSpec))
  }

  /**
   * Create an [[AlterTableAddPartitionCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER TABLE table ADD [IF NOT EXISTS] PARTITION spec [LOCATION 'loc1']
   *   ALTER VIEW view ADD [IF NOT EXISTS] PARTITION spec
   * }}}
   *
   * ALTER VIEW ... ADD PARTITION ... is not supported because the concept of partitioning
   * is associated with physical tables
   */
  override def visitAddTablePartition(
      ctx: AddTablePartitionContext): LogicalPlan = withOrigin(ctx) {
    if (ctx.VIEW != null) {
      operationNotAllowed("ALTER VIEW ... ADD PARTITION", ctx)
    }
    // Create partition spec to location mapping.
    val specsAndLocs = if (ctx.partitionSpec.isEmpty) {
      ctx.partitionSpecLocation.asScala.map {
        splCtx =>
          val spec = visitNonOptionalPartitionSpec(splCtx.partitionSpec)
          val location = Option(splCtx.locationSpec).map(visitLocationSpec)
          spec -> location
      }
    } else {
      // Alter View: the location clauses are not allowed.
      ctx.partitionSpec.asScala.map(visitNonOptionalPartitionSpec(_) -> None)
    }
    AlterTableAddPartitionCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      specsAndLocs,
      ctx.EXISTS != null)
  }

  /**
   * Create an [[AlterTableRenamePartitionCommand]] command
   *
   * For example:
   * {{{
   *   ALTER TABLE table PARTITION spec1 RENAME TO PARTITION spec2;
   * }}}
   */
  override def visitRenameTablePartition(
      ctx: RenameTablePartitionContext): LogicalPlan = withOrigin(ctx) {
    AlterTableRenamePartitionCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      visitNonOptionalPartitionSpec(ctx.from),
      visitNonOptionalPartitionSpec(ctx.to))
  }

  /**
   * Create an [[AlterTableDropPartitionCommand]] command
   *
   * For example:
   * {{{
   *   ALTER TABLE table DROP [IF EXISTS] PARTITION spec1[, PARTITION spec2, ...] [PURGE];
   *   ALTER VIEW view DROP [IF EXISTS] PARTITION spec1[, PARTITION spec2, ...];
   * }}}
   *
   * ALTER VIEW ... DROP PARTITION ... is not supported because the concept of partitioning
   * is associated with physical tables
   */
  override def visitDropTablePartitions(
      ctx: DropTablePartitionsContext): LogicalPlan = withOrigin(ctx) {
    if (ctx.VIEW != null) {
      operationNotAllowed("ALTER VIEW ... DROP PARTITION", ctx)
    }
    AlterTableDropPartitionCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      ctx.partitionSpec.asScala.map(visitNonOptionalPartitionSpec),
      ifExists = ctx.EXISTS != null,
      purge = ctx.PURGE != null,
      retainData = false)
  }

  /**
   * Create an [[AlterTableRecoverPartitionsCommand]] command
   *
   * For example:
   * {{{
   *   ALTER TABLE table RECOVER PARTITIONS;
   * }}}
   */
  override def visitRecoverPartitions(
      ctx: RecoverPartitionsContext): LogicalPlan = withOrigin(ctx) {
    AlterTableRecoverPartitionsCommand(visitTableIdentifier(ctx.tableIdentifier))
  }

  /**
   * Create an [[AlterTableSetLocationCommand]] command for a partition.
   *
   * For example:
   * {{{
   *   ALTER TABLE table PARTITION spec SET LOCATION "loc";
   * }}}
   */
  override def visitSetPartitionLocation(
      ctx: SetPartitionLocationContext): LogicalPlan = withOrigin(ctx) {
    AlterTableSetLocationCommand(
      visitTableIdentifier(ctx.tableIdentifier),
      Some(visitNonOptionalPartitionSpec(ctx.partitionSpec)),
      visitLocationSpec(ctx.locationSpec))
  }

  /**
   * Create a [[AlterTableChangeColumnCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER TABLE table [PARTITION partition_spec]
   *   CHANGE [COLUMN] column_old_name column_new_name column_dataType [COMMENT column_comment]
   *   [FIRST | AFTER column_name];
   * }}}
   */
  override def visitChangeColumn(ctx: ChangeColumnContext): LogicalPlan = withOrigin(ctx) {
    if (ctx.partitionSpec != null) {
      operationNotAllowed("ALTER TABLE table PARTITION partition_spec CHANGE COLUMN", ctx)
    }

    if (ctx.colPosition != null) {
      operationNotAllowed(
        "ALTER TABLE table [PARTITION partition_spec] CHANGE COLUMN ... FIRST | AFTER otherCol",
        ctx)
    }

    AlterTableChangeColumnCommand(
      tableName = visitTableIdentifier(ctx.tableIdentifier),
      columnName = ctx.identifier.getText,
      newColumn = visitColType(ctx.colType))
  }

  /**
   * Convert a nested constants list into a sequence of string sequences.
   */
  override def visitNestedConstantList(
      ctx: NestedConstantListContext): Seq[Seq[String]] = withOrigin(ctx) {
    ctx.constantList.asScala.map(visitConstantList)
  }

  /**
   * Convert a constants list into a String sequence.
   */
  override def visitConstantList(ctx: ConstantListContext): Seq[String] = withOrigin(ctx) {
    ctx.constant.asScala.map(visitStringConstant)
  }

  /**
   * Fail an unsupported Hive native command.
   */
  override def visitFailNativeCommand(
    ctx: FailNativeCommandContext): LogicalPlan = withOrigin(ctx) {
    val keywords = if (ctx.unsupportedHiveNativeCommands != null) {
      ctx.unsupportedHiveNativeCommands.children.asScala.collect {
        case n: TerminalNode => n.getText
      }.mkString(" ")
    } else {
      // SET ROLE is the exception to the rule, because we handle this before other SET commands.
      "SET ROLE"
    }
    operationNotAllowed(keywords, ctx)
  }

  /**
   * Create a [[AddFileCommand]], [[AddJarCommand]], [[ListFilesCommand]] or [[ListJarsCommand]]
   * command depending on the requested operation on resources.
   * Expected format:
   * {{{
   *   ADD (FILE[S] <filepath ...> | JAR[S] <jarpath ...>)
   *   LIST (FILE[S] [filepath ...] | JAR[S] [jarpath ...])
   * }}}
   */
  override def visitManageResource(ctx: ManageResourceContext): LogicalPlan = withOrigin(ctx) {
    val mayebePaths = remainder(ctx.identifier).trim
    ctx.op.getType match {
      case SqlBaseParser.ADD =>
        ctx.identifier.getText.toLowerCase(Locale.ROOT) match {
          case "file" => AddFileCommand(mayebePaths)
          case "jar" => AddJarCommand(mayebePaths)
          case other => operationNotAllowed(s"ADD with resource type '$other'", ctx)
        }
      case SqlBaseParser.LIST =>
        ctx.identifier.getText.toLowerCase(Locale.ROOT) match {
          case "files" | "file" =>
            if (mayebePaths.length > 0) {
              ListFilesCommand(mayebePaths.split("\\s+"))
            } else {
              ListFilesCommand()
            }
          case "jars" | "jar" =>
            if (mayebePaths.length > 0) {
              ListJarsCommand(mayebePaths.split("\\s+"))
            } else {
              ListJarsCommand()
            }
          case other => operationNotAllowed(s"LIST with resource type '$other'", ctx)
        }
      case _ => operationNotAllowed(s"Other types of operation on resources", ctx)
    }
  }

  /**
   * Create a Hive serde table, returning a [[CreateTable]] logical plan.
   *
   * This is a legacy syntax for Hive compatibility, we recommend users to use the Spark SQL
   * CREATE TABLE syntax to create Hive serde table, e.g. "CREATE TABLE ... USING hive ..."
   *
   * Note: several features are currently not supported - temporary tables, bucketing,
   * skewed columns and storage handlers (STORED BY).
   *
   * Expected format:
   * {{{
   *   CREATE [EXTERNAL] TABLE [IF NOT EXISTS] [db_name.]table_name
   *   [(col1[:] data_type [COMMENT col_comment], ...)]
   *   create_table_clauses
   *   [AS select_statement];
   *
   *   create_table_clauses (order insensitive):
   *     [COMMENT table_comment]
   *     [PARTITIONED BY (col2[:] data_type [COMMENT col_comment], ...)]
   *     [ROW FORMAT row_format]
   *     [STORED AS file_format]
   *     [LOCATION path]
   *     [TBLPROPERTIES (property_name=property_value, ...)]
   * }}}
   */
  override def visitCreateHiveTable(ctx: CreateHiveTableContext): LogicalPlan = withOrigin(ctx) {
    val (ident, temp, ifNotExists, external) = visitCreateTableHeader(ctx.createTableHeader)
    // TODO: implement temporary tables
    if (temp) {
      throw new ParseException(
        "CREATE TEMPORARY TABLE is not supported yet. " +
          "Please use CREATE TEMPORARY VIEW as an alternative.", ctx)
    }
    if (ctx.skewSpec.size > 0) {
      operationNotAllowed("CREATE TABLE ... SKEWED BY", ctx)
    }

    checkDuplicateClauses(ctx.TBLPROPERTIES, "TBLPROPERTIES", ctx)
    checkDuplicateClauses(ctx.PARTITIONED, "PARTITIONED BY", ctx)
    checkDuplicateClauses(ctx.COMMENT, "COMMENT", ctx)
    checkDuplicateClauses(ctx.bucketSpec(), "CLUSTERED BY", ctx)
    checkDuplicateClauses(ctx.createFileFormat, "STORED AS/BY", ctx)
    checkDuplicateClauses(ctx.rowFormat, "ROW FORMAT", ctx)
    checkDuplicateClauses(ctx.locationSpec, "LOCATION", ctx)

    val dataCols = Option(ctx.columns).map(visitColTypeList).getOrElse(Nil)
    val partitionCols = Option(ctx.partitionColumns).map(visitColTypeList).getOrElse(Nil)
    val properties = Option(ctx.tableProps).map(visitPropertyKeyValues).getOrElse(Map.empty)
    val selectQuery = Option(ctx.query).map(plan)
    val bucketSpec = ctx.bucketSpec().asScala.headOption.map(visitBucketSpec)

    // Note: Hive requires partition columns to be distinct from the schema, so we need
    // to include the partition columns here explicitly
    val schema = StructType(dataCols ++ partitionCols)

    // Storage format
    val defaultStorage = HiveSerDe.getDefaultStorage(conf)
    validateRowFormatFileFormat(ctx.rowFormat.asScala, ctx.createFileFormat.asScala, ctx)
    val fileStorage = ctx.createFileFormat.asScala.headOption.map(visitCreateFileFormat)
      .getOrElse(CatalogStorageFormat.empty)
    val rowStorage = ctx.rowFormat.asScala.headOption.map(visitRowFormat)
      .getOrElse(CatalogStorageFormat.empty)
    val location = ctx.locationSpec.asScala.headOption.map(visitLocationSpec)
    // If we are creating an EXTERNAL table, then the LOCATION field is required
    if (external && location.isEmpty) {
      operationNotAllowed("CREATE EXTERNAL TABLE must be accompanied by LOCATION", ctx)
    }

    val locUri = location.map(CatalogUtils.stringToURI(_))
    val storage = CatalogStorageFormat(
      locationUri = locUri,
      inputFormat = fileStorage.inputFormat.orElse(defaultStorage.inputFormat),
      outputFormat = fileStorage.outputFormat.orElse(defaultStorage.outputFormat),
      serde = rowStorage.serde.orElse(fileStorage.serde).orElse(defaultStorage.serde),
      compressed = false,
      properties = rowStorage.properties ++ fileStorage.properties)
    // If location is defined, we'll assume this is an external table.
    // Otherwise, we may accidentally delete existing data.
    val tableType = if (external || location.isDefined) {
      CatalogTableType.EXTERNAL
    } else {
      CatalogTableType.MANAGED
    }

    val name = tableIdentifier(ident, "CREATE TABLE ... STORED AS ...", ctx)

    // TODO support the sql text - have a proper location for this!
    val tableDesc = CatalogTable(
      identifier = name,
      tableType = tableType,
      storage = storage,
      schema = schema,
      bucketSpec = bucketSpec,
      provider = Some(DDLUtils.HIVE_PROVIDER),
      partitionColumnNames = partitionCols.map(_.name),
      properties = properties,
      comment = Option(ctx.comment).map(string))

    val mode = if (ifNotExists) SaveMode.Ignore else SaveMode.ErrorIfExists

    selectQuery match {
      case Some(q) =>
        // Don't allow explicit specification of schema for CTAS.
        if (dataCols.nonEmpty) {
          operationNotAllowed(
            "Schema may not be specified in a Create Table As Select (CTAS) statement",
            ctx)
        }

        // When creating partitioned table with CTAS statement, we can't specify data type for the
        // partition columns.
        if (partitionCols.nonEmpty) {
          val errorMessage = "Create Partitioned Table As Select cannot specify data type for " +
            "the partition columns of the target table."
          operationNotAllowed(errorMessage, ctx)
        }

        // Hive CTAS supports dynamic partition by specifying partition column names.
        val partitionColumnNames =
          Option(ctx.partitionColumnNames)
            .map(visitIdentifierList(_).toArray)
            .getOrElse(Array.empty[String])

        val tableDescWithPartitionColNames =
          tableDesc.copy(partitionColumnNames = partitionColumnNames)

        val hasStorageProperties = (ctx.createFileFormat.size != 0) || (ctx.rowFormat.size != 0)
        if (conf.convertCTAS && !hasStorageProperties) {
          // At here, both rowStorage.serdeProperties and fileStorage.serdeProperties
          // are empty Maps.
          val newTableDesc = tableDescWithPartitionColNames.copy(
            storage = CatalogStorageFormat.empty.copy(locationUri = locUri),
            provider = Some(conf.defaultDataSourceName))
          CreateTable(newTableDesc, mode, Some(q))
        } else {
          CreateTable(tableDescWithPartitionColNames, mode, Some(q))
        }
      case None =>
        // When creating partitioned table, we must specify data type for the partition columns.
        if (Option(ctx.partitionColumnNames).isDefined) {
          val errorMessage = "Must specify a data type for each partition column while creating " +
            "Hive partitioned table."
          operationNotAllowed(errorMessage, ctx)
        }

        CreateTable(tableDesc, mode, None)
    }
  }

  /**
   * Create a [[CreateTableLikeCommand]] command.
   *
   * For example:
   * {{{
   *   CREATE TABLE [IF NOT EXISTS] [db_name.]table_name
   *   LIKE [other_db_name.]existing_table_name [locationSpec]
   * }}}
   */
  override def visitCreateTableLike(ctx: CreateTableLikeContext): LogicalPlan = withOrigin(ctx) {
    val targetTable = visitTableIdentifier(ctx.target)
    val sourceTable = visitTableIdentifier(ctx.source)
    val location = Option(ctx.locationSpec).map(visitLocationSpec)
    CreateTableLikeCommand(targetTable, sourceTable, location, ctx.EXISTS != null)
  }

  /**
   * Create a [[CatalogStorageFormat]] for creating tables.
   *
   * Format: STORED AS ...
   */
  override def visitCreateFileFormat(
      ctx: CreateFileFormatContext): CatalogStorageFormat = withOrigin(ctx) {
    (ctx.fileFormat, ctx.storageHandler) match {
      // Expected format: INPUTFORMAT input_format OUTPUTFORMAT output_format
      case (c: TableFileFormatContext, null) =>
        visitTableFileFormat(c)
      // Expected format: SEQUENCEFILE | TEXTFILE | RCFILE | ORC | PARQUET | AVRO
      case (c: GenericFileFormatContext, null) =>
        visitGenericFileFormat(c)
      case (null, storageHandler) =>
        operationNotAllowed("STORED BY", ctx)
      case _ =>
        throw new ParseException("Expected either STORED AS or STORED BY, not both", ctx)
    }
  }

  /**
   * Create a [[CatalogStorageFormat]].
   */
  override def visitTableFileFormat(
      ctx: TableFileFormatContext): CatalogStorageFormat = withOrigin(ctx) {
    CatalogStorageFormat.empty.copy(
      inputFormat = Option(string(ctx.inFmt)),
      outputFormat = Option(string(ctx.outFmt)))
  }

  /**
   * Resolve a [[HiveSerDe]] based on the name given and return it as a [[CatalogStorageFormat]].
   */
  override def visitGenericFileFormat(
      ctx: GenericFileFormatContext): CatalogStorageFormat = withOrigin(ctx) {
    val source = ctx.identifier.getText
    HiveSerDe.sourceToSerDe(source) match {
      case Some(s) =>
        CatalogStorageFormat.empty.copy(
          inputFormat = s.inputFormat,
          outputFormat = s.outputFormat,
          serde = s.serde)
      case None =>
        operationNotAllowed(s"STORED AS with file format '$source'", ctx)
    }
  }

  /**
   * Create a [[CatalogStorageFormat]] used for creating tables.
   *
   * Example format:
   * {{{
   *   SERDE serde_name [WITH SERDEPROPERTIES (k1=v1, k2=v2, ...)]
   * }}}
   *
   * OR
   *
   * {{{
   *   DELIMITED [FIELDS TERMINATED BY char [ESCAPED BY char]]
   *   [COLLECTION ITEMS TERMINATED BY char]
   *   [MAP KEYS TERMINATED BY char]
   *   [LINES TERMINATED BY char]
   *   [NULL DEFINED AS char]
   * }}}
   */
  private def visitRowFormat(ctx: RowFormatContext): CatalogStorageFormat = withOrigin(ctx) {
    ctx match {
      case serde: RowFormatSerdeContext => visitRowFormatSerde(serde)
      case delimited: RowFormatDelimitedContext => visitRowFormatDelimited(delimited)
    }
  }

  /**
   * Create SERDE row format name and properties pair.
   */
  override def visitRowFormatSerde(
      ctx: RowFormatSerdeContext): CatalogStorageFormat = withOrigin(ctx) {
    import ctx._
    CatalogStorageFormat.empty.copy(
      serde = Option(string(name)),
      properties = Option(tablePropertyList).map(visitPropertyKeyValues).getOrElse(Map.empty))
  }

  /**
   * Create a delimited row format properties object.
   */
  override def visitRowFormatDelimited(
      ctx: RowFormatDelimitedContext): CatalogStorageFormat = withOrigin(ctx) {
    // Collect the entries if any.
    def entry(key: String, value: Token): Seq[(String, String)] = {
      Option(value).toSeq.map(x => key -> string(x))
    }
    // TODO we need proper support for the NULL format.
    val entries =
      entry("field.delim", ctx.fieldsTerminatedBy) ++
        entry("serialization.format", ctx.fieldsTerminatedBy) ++
        entry("escape.delim", ctx.escapedBy) ++
        // The following typo is inherited from Hive...
        entry("colelction.delim", ctx.collectionItemsTerminatedBy) ++
        entry("mapkey.delim", ctx.keysTerminatedBy) ++
        Option(ctx.linesSeparatedBy).toSeq.map { token =>
          val value = string(token)
          validate(
            value == "\n",
            s"LINES TERMINATED BY only supports newline '\\n' right now: $value",
            ctx)
          "line.delim" -> value
        }
    CatalogStorageFormat.empty.copy(properties = entries.toMap)
  }

  /**
   * Throw a [[ParseException]] if the user specified incompatible SerDes through ROW FORMAT
   * and STORED AS.
   *
   * The following are allowed. Anything else is not:
   *   ROW FORMAT SERDE ... STORED AS [SEQUENCEFILE | RCFILE | TEXTFILE]
   *   ROW FORMAT DELIMITED ... STORED AS TEXTFILE
   *   ROW FORMAT ... STORED AS INPUTFORMAT ... OUTPUTFORMAT ...
   */
  private def validateRowFormatFileFormat(
      rowFormatCtx: RowFormatContext,
      createFileFormatCtx: CreateFileFormatContext,
      parentCtx: ParserRuleContext): Unit = {
    if (rowFormatCtx == null || createFileFormatCtx == null) {
      return
    }
    (rowFormatCtx, createFileFormatCtx.fileFormat) match {
      case (_, ffTable: TableFileFormatContext) => // OK
      case (rfSerde: RowFormatSerdeContext, ffGeneric: GenericFileFormatContext) =>
        ffGeneric.identifier.getText.toLowerCase(Locale.ROOT) match {
          case ("sequencefile" | "textfile" | "rcfile") => // OK
          case fmt =>
            operationNotAllowed(
              s"ROW FORMAT SERDE is incompatible with format '$fmt', which also specifies a serde",
              parentCtx)
        }
      case (rfDelimited: RowFormatDelimitedContext, ffGeneric: GenericFileFormatContext) =>
        ffGeneric.identifier.getText.toLowerCase(Locale.ROOT) match {
          case "textfile" => // OK
          case fmt => operationNotAllowed(
            s"ROW FORMAT DELIMITED is only compatible with 'textfile', not '$fmt'", parentCtx)
        }
      case _ =>
        // should never happen
        def str(ctx: ParserRuleContext): String = {
          (0 until ctx.getChildCount).map { i => ctx.getChild(i).getText }.mkString(" ")
        }
        operationNotAllowed(
          s"Unexpected combination of ${str(rowFormatCtx)} and ${str(createFileFormatCtx)}",
          parentCtx)
    }
  }

  private def validateRowFormatFileFormat(
      rowFormatCtx: Seq[RowFormatContext],
      createFileFormatCtx: Seq[CreateFileFormatContext],
      parentCtx: ParserRuleContext): Unit = {
    if (rowFormatCtx.size == 1 && createFileFormatCtx.size == 1) {
      validateRowFormatFileFormat(rowFormatCtx.head, createFileFormatCtx.head, parentCtx)
    }
  }

  /**
   * Create or replace a view. This creates a [[CreateViewCommand]] command.
   *
   * For example:
   * {{{
   *   CREATE [OR REPLACE] [[GLOBAL] TEMPORARY] VIEW [IF NOT EXISTS] [db_name.]view_name
   *   [(column_name [COMMENT column_comment], ...) ]
   *   [COMMENT view_comment]
   *   [TBLPROPERTIES (property_name = property_value, ...)]
   *   AS SELECT ...;
   * }}}
   */
  override def visitCreateView(ctx: CreateViewContext): LogicalPlan = withOrigin(ctx) {
    if (ctx.identifierList != null) {
      operationNotAllowed("CREATE VIEW ... PARTITIONED ON", ctx)
    } else {
      val userSpecifiedColumns = Option(ctx.identifierCommentList).toSeq.flatMap { icl =>
        icl.identifierComment.asScala.map { ic =>
          ic.identifier.getText -> Option(ic.STRING).map(string)
        }
      }

      val viewType = if (ctx.TEMPORARY == null) {
        PersistedView
      } else if (ctx.GLOBAL != null) {
        GlobalTempView
      } else {
        LocalTempView
      }

      CreateViewCommand(
        name = visitTableIdentifier(ctx.tableIdentifier),
        userSpecifiedColumns = userSpecifiedColumns,
        comment = Option(ctx.STRING).map(string),
        properties = Option(ctx.tablePropertyList).map(visitPropertyKeyValues).getOrElse(Map.empty),
        originalText = Option(source(ctx.query)),
        child = plan(ctx.query),
        allowExisting = ctx.EXISTS != null,
        replace = ctx.REPLACE != null,
        viewType = viewType)
    }
  }

  /**
   * Alter the query of a view. This creates a [[AlterViewAsCommand]] command.
   *
   * For example:
   * {{{
   *   ALTER VIEW [db_name.]view_name AS SELECT ...;
   * }}}
   */
  override def visitAlterViewQuery(ctx: AlterViewQueryContext): LogicalPlan = withOrigin(ctx) {
    AlterViewAsCommand(
      name = visitTableIdentifier(ctx.tableIdentifier),
      originalText = source(ctx.query),
      query = plan(ctx.query))
  }

  /**
   * Create a [[ScriptInputOutputSchema]].
   */
  override protected def withScriptIOSchema(
      ctx: QuerySpecificationContext,
      inRowFormat: RowFormatContext,
      recordWriter: Token,
      outRowFormat: RowFormatContext,
      recordReader: Token,
      schemaLess: Boolean): ScriptInputOutputSchema = {
    if (recordWriter != null || recordReader != null) {
      // TODO: what does this message mean?
      throw new ParseException(
        "Unsupported operation: Used defined record reader/writer classes.", ctx)
    }

    // Decode and input/output format.
    type Format = (Seq[(String, String)], Option[String], Seq[(String, String)], Option[String])
    def format(
        fmt: RowFormatContext,
        configKey: String,
        defaultConfigValue: String): Format = fmt match {
      case c: RowFormatDelimitedContext =>
        // TODO we should use the visitRowFormatDelimited function here. However HiveScriptIOSchema
        // expects a seq of pairs in which the old parsers' token names are used as keys.
        // Transforming the result of visitRowFormatDelimited would be quite a bit messier than
        // retrieving the key value pairs ourselves.
        def entry(key: String, value: Token): Seq[(String, String)] = {
          Option(value).map(t => key -> t.getText).toSeq
        }
        val entries = entry("TOK_TABLEROWFORMATFIELD", c.fieldsTerminatedBy) ++
          entry("TOK_TABLEROWFORMATCOLLITEMS", c.collectionItemsTerminatedBy) ++
          entry("TOK_TABLEROWFORMATMAPKEYS", c.keysTerminatedBy) ++
          entry("TOK_TABLEROWFORMATLINES", c.linesSeparatedBy) ++
          entry("TOK_TABLEROWFORMATNULL", c.nullDefinedAs)

        (entries, None, Seq.empty, None)

      case c: RowFormatSerdeContext =>
        // Use a serde format.
        val CatalogStorageFormat(None, None, None, Some(name), _, props) = visitRowFormatSerde(c)

        // SPARK-10310: Special cases LazySimpleSerDe
        val recordHandler = if (name == "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe") {
          Option(conf.getConfString(configKey, defaultConfigValue))
        } else {
          None
        }
        (Seq.empty, Option(name), props.toSeq, recordHandler)

      case null =>
        // Use default (serde) format.
        val name = conf.getConfString("hive.script.serde",
          "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")
        val props = Seq("field.delim" -> "\t")
        val recordHandler = Option(conf.getConfString(configKey, defaultConfigValue))
        (Nil, Option(name), props, recordHandler)
    }

    val (inFormat, inSerdeClass, inSerdeProps, reader) =
      format(
        inRowFormat, "hive.script.recordreader", "org.apache.hadoop.hive.ql.exec.TextRecordReader")

    val (outFormat, outSerdeClass, outSerdeProps, writer) =
      format(
        outRowFormat, "hive.script.recordwriter",
        "org.apache.hadoop.hive.ql.exec.TextRecordWriter")

    ScriptInputOutputSchema(
      inFormat, outFormat,
      inSerdeClass, outSerdeClass,
      inSerdeProps, outSerdeProps,
      reader, writer,
      schemaLess)
  }

  /**
   * Create a clause for DISTRIBUTE BY.
   */
  override protected def withRepartitionByExpression(
      ctx: QueryOrganizationContext,
      expressions: Seq[Expression],
      query: LogicalPlan): LogicalPlan = {
    RepartitionByExpression(expressions, query, conf.numShufflePartitions)
  }

  /**
   * Return the parameters for [[InsertIntoDir]] logical plan.
   *
   * Expected format:
   * {{{
   *   INSERT OVERWRITE DIRECTORY
   *   [path]
   *   [OPTIONS table_property_list]
   *   select_statement;
   * }}}
   */
  override def visitInsertOverwriteDir(
      ctx: InsertOverwriteDirContext): InsertDirParams = withOrigin(ctx) {
    if (ctx.LOCAL != null) {
      throw new ParseException(
        "LOCAL is not supported in INSERT OVERWRITE DIRECTORY to data source", ctx)
    }

    val options = Option(ctx.options).map(visitPropertyKeyValues).getOrElse(Map.empty)
    var storage = DataSource.buildStorageFormatFromOptions(options)

    val path = Option(ctx.path).map(string).getOrElse("")

    if (!(path.isEmpty ^ storage.locationUri.isEmpty)) {
      throw new ParseException(
        "Directory path and 'path' in OPTIONS should be specified one, but not both", ctx)
    }

    if (!path.isEmpty) {
      val customLocation = Some(CatalogUtils.stringToURI(path))
      storage = storage.copy(locationUri = customLocation)
    }

    val provider = ctx.tableProvider.qualifiedName.getText

    (false, storage, Some(provider))
  }

  /**
   * Return the parameters for [[InsertIntoDir]] logical plan.
   *
   * Expected format:
   * {{{
   *   INSERT OVERWRITE [LOCAL] DIRECTORY
   *   path
   *   [ROW FORMAT row_format]
   *   [STORED AS file_format]
   *   select_statement;
   * }}}
   */
  override def visitInsertOverwriteHiveDir(
      ctx: InsertOverwriteHiveDirContext): InsertDirParams = withOrigin(ctx) {
    validateRowFormatFileFormat(ctx.rowFormat, ctx.createFileFormat, ctx)
    val rowStorage = Option(ctx.rowFormat).map(visitRowFormat)
      .getOrElse(CatalogStorageFormat.empty)
    val fileStorage = Option(ctx.createFileFormat).map(visitCreateFileFormat)
      .getOrElse(CatalogStorageFormat.empty)

    val path = string(ctx.path)
    // The path field is required
    if (path.isEmpty) {
      operationNotAllowed("INSERT OVERWRITE DIRECTORY must be accompanied by path", ctx)
    }

    val defaultStorage = HiveSerDe.getDefaultStorage(conf)

    val storage = CatalogStorageFormat(
      locationUri = Some(CatalogUtils.stringToURI(path)),
      inputFormat = fileStorage.inputFormat.orElse(defaultStorage.inputFormat),
      outputFormat = fileStorage.outputFormat.orElse(defaultStorage.outputFormat),
      serde = rowStorage.serde.orElse(fileStorage.serde).orElse(defaultStorage.serde),
      compressed = false,
      properties = rowStorage.properties ++ fileStorage.properties)

    (ctx.LOCAL != null, storage, Some(DDLUtils.HIVE_PROVIDER))
  }
}
