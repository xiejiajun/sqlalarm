package dt.sql.alarm.filter

import dt.sql.alarm.conf.{AlarmPolicyConf, AlarmRuleConf}
import dt.sql.alarm.core.RecordDetail._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import tech.sqlclub.common.exception.SQLClubException
import tech.sqlclub.common.log.Logging
import org.apache.spark.sql.types.{MapType, StringType}
import dt.sql.alarm.core.Constants.{SQL_FIELD_RANK_NAME, SQL_FIELD_VALUE_NAME}
import org.apache.spark.sql.catalyst.plans.logical.{Project, Union}
import org.apache.spark.sql.expressions.Window

object SQLFilter extends Logging {

  lazy private val requireCols = getAllSQLFieldName
  lazy private val requireSchema = getAllFieldSchema.map(f => (f.name, f.dataType)).toMap

  def process(df:Dataset[Row], ruleConf:AlarmRuleConf, policy:AlarmPolicyConf):DataFrame = {
    val spark = df.sparkSession

    val source_ = ruleConf.source
    val structures = ruleConf.filter.structure
    val tableName = ruleConf.filter.table
    val sql = ruleConf.filter.sql.trim

    val fields = structures.map{
      field =>
        s"cast(get_json_object($SQL_FIELD_VALUE_NAME, '${field.xpath}') as ${field.`type`}) as ${field.name}"
    }

    val table = df.filter( col(source) === source_.`type` and col(topic) === source_.topic ).selectExpr(fields :_*)

    logInfo(s"SQLFilter SQL table [ $tableName ] schema: ")
    table.printSchema()

    table.createOrReplaceTempView(tableName)

    def checkSQLSyntax(sql: String): (Boolean, String) = {
      try {
        // 这只是检验sql语法
        val logicalPlan = spark.sessionState.sqlParser.parsePlan(sql)
        if (!logicalPlan.resolved) {
          // 这边才会按表结构去校验
          spark.sessionState.executePlan(logicalPlan).assertAnalyzed()
          (true, "")
        } else {
          (true, "")
        }
      } catch {
        case e:Exception =>
          (false, e.getMessage)
      }
    }

    val ck = checkSQLSyntax(sql)
    if (!ck._1) throw new SQLClubException(s"input filter sql error! item_id: ${ruleConf.item_id}"+ ".sql:\n" + sql + " .\n\n" + ck._2)

    logInfo(s"input ruleConf:[source:${source_.`type`}, topic:$topic, tableName:$tableName] exec SQL: $sql")

    val sqlPlan = spark.sql(sql).queryExecution.analyzed

    val sqlCols = sqlPlan.output.map{att => att.name.toLowerCase}

    val b = (true /: requireCols){(x,y) => x && sqlCols.contains(y)}

    if(!b){
      logError("exec sql output cols must contains col list: " + requireCols)
      throw new SQLClubException("exec sql output cols error! find cols: [" + sqlCols.mkString(",") + "],requires: [" + requireCols.mkString(",") + "]!")
    }

    /*
    root
      |-- job_id: string (nullable = true)
      |-- job_stat: string (nullable = true)
      |-- event_time: string (nullable = true)
      |-- message: string (nullable = true)
      |-- context: string (nullable = true)
      |-- title: string (nullable = false)
      |-- platform: string (nullable = false)
      |-- item_id: string (nullable = false)
      |-- source: string (nullable = false)
      |-- topic: string (nullable = false)
      |-- alarm: integer (nullable = false)
    */
    val filtertab = spark.sql(sql).selectExpr(requireCols :_* ).selectExpr("*" ,
      s"'${ruleConf.title}' as $title",
      s"'${ruleConf.platform}' as $platform",
      s"'${ruleConf.item_id}' as $item_id",
      s"'${source_.`type`}' as $source",
      s"'${source_.topic}' as $topic"
    ).withColumn(context, to_json(col(context)))
     .withColumn(alarm, lit(1))

//    logInfo("SQLFilter SQL table filter result schema: ")
//    filtertab.printSchema()

    import dt.sql.alarm.conf.PolicyType._
    val result = if (policy != null && policy.policy.`type`.isScale){

      // 目前过滤sql只支持单条简单sql 可以union
      val project = sqlPlan match {
        case p if p.isInstanceOf[Union] => p.children.head.asInstanceOf[Project]
        case p if p.isInstanceOf[Project] => p.asInstanceOf[Project]
      }

      val output = project.projectList.map(_.sql).mkString(",")
      val sql = s"SELECT $output FROM $tableName"

      logInfo("Simplified SQL: \n" + sql)
      if (!checkSQLSyntax(sql)._1) throw new SQLClubException(s"sql error! item_id: ${ruleConf.item_id}"+ ".sql:\n" + sql + " .\n\n" + ck._2)

      val table = spark.sql(sql)
        .withColumn(item_id, lit(ruleConf.item_id))
        .withColumn(context, to_json(col(context)))
        .withColumn(SQL_FIELD_RANK_NAME, row_number()         // rank
          over( Window.partitionBy(item_id, job_id,job_stat,context,message) orderBy col(event_time).desc ) )

      val alarmTable = filtertab.withColumn(SQL_FIELD_RANK_NAME, row_number()         // rank
        over( Window.partitionBy(item_id, job_id,job_stat,context,message) orderBy col(event_time).desc ) )

      // 增加row_number 编号为了防止相同记录产生笛卡尔

      table.join(alarmTable, Seq(item_id,job_id,job_stat,event_time,context,message,SQL_FIELD_RANK_NAME), "left_outer")
        .withColumn(alarm, when(isnull(col(alarm)), 0).otherwise(1)).drop(SQL_FIELD_RANK_NAME)

    } else {
      filtertab
    }

    val schema = result.schema.map{
      structField =>
        val name = structField.name
        val dataType = if(structField.dataType.isInstanceOf[MapType]) MapType(StringType,StringType) else structField.dataType
        (name, dataType)
    }.toMap

    if ( !requireSchema.equals(schema) ){
      throw new SQLClubException(s"the filter sql exec result schema error!item_id: ${ruleConf.item_id}, schema: ${filtertab.schema}")
    }

    // 为了过滤脏数据 if job_id and event_time is null
    result.filter(not(isnull(col(job_id))) and not(isnull(col(event_time))))
  }
}
