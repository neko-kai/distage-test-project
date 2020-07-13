package dataStrorage

import com.datastax.driver.core.querybuilder.QueryBuilder
import covid19.model.CovidData
import com.datastax.driver.core.{Cluster, Row, Session}
import izumi.distage.model.definition.DIResource
import izumi.fundamentals.platform.functional.Identity
import zio.{IO, UIO}

final case class CassandraConfig(
  address: String,
  keySpace: String
)

class CassandraTransactor(val config: CassandraConfig) {
  lazy val cluster: Cluster = Cluster.builder().addContactPoint(config.address).build()
  lazy val session: Session = cluster.connect(config.keySpace)

  def close(): Unit = cluster.close()
}

class CassandraResource(val config: CassandraConfig) extends DIResource.Simple[CassandraTransactor] {
  override def acquire: Identity[CassandraTransactor] = {
    new CassandraTransactor(config)
  }

  override def release(resource: CassandraTransactor): Identity[Unit] = {
    resource.close()
  }
}

class CassandraStorage(val transactor: CassandraTransactor) extends DataStorage {
  object CovidTable {
    val name: String = "covid"

    object Fields {
      val location: String = "location"
      val isoCode: String = "iso_code"
      val confirmed: String = "confirmed"
      val dead: String = "dead"
      val recovered: String = "recovered"
    }
  }

  override def create: UIO[Unit] = {
    val query =
      s"""
        |CREATE TABLE IF NOT EXISTS ${CovidTable.name} (
        |${CovidTable.Fields.location} TEXT PRIMARY KEY,
        |${CovidTable.Fields.isoCode} TEXT,
        |${CovidTable.Fields.confirmed} INT,
        |${CovidTable.Fields.dead} INT,
        |${CovidTable.Fields.recovered} INT)""".stripMargin

    IO.effectTotal(transactor.session.execute(query))
  }

  override def save(data: Seq[CovidData]): UIO[Unit] = {
    IO.effectTotal {
    data
      .map(item => QueryBuilder
        .update(CovidTable.name)
        .`with`(QueryBuilder.set(CovidTable.Fields.confirmed, item.confirmed))
        .and(QueryBuilder.set(CovidTable.Fields.recovered, item.recovered))
        .and(QueryBuilder.set(CovidTable.Fields.dead, item.deaths))
        .and(QueryBuilder.set(CovidTable.Fields.isoCode, item.isoCode.fold("NaN")(x => x)))
        .where(QueryBuilder.eq(CovidTable.Fields.location, item.locationName))
      ).foreach(transactor.session.execute(_))

      data
        .map(item => QueryBuilder
          .insertInto(CovidTable.name)
          .ifNotExists()
          .value(CovidTable.Fields.location, item.locationName)
          .value(CovidTable.Fields.isoCode, item.isoCode.fold("NaN")(x => x))
          .value(CovidTable.Fields.confirmed, item.confirmed)
          .value(CovidTable.Fields.dead, item.deaths)
          .value(CovidTable.Fields.recovered, item.recovered))
        .foreach(transactor.session.execute(_))
    }
  }

  override def getByLocation(location: String): UIO[Option[CovidData]] = {
    implicit def rowToCovidData(row: Row): CovidData = {
      CovidData(
        row.getString(CovidTable.Fields.location),
        Option(row.getString(CovidTable.Fields.isoCode)),
        row.getInt(CovidTable.Fields.confirmed),
        row.getInt(CovidTable.Fields.recovered),
        row.getInt(CovidTable.Fields.dead)
      )
    }

    val query = QueryBuilder.select().from("covid").where(QueryBuilder.eq(CovidTable.Fields.location, location))

    IO.effectTotal {
      transactor.session.execute(query).one() match {
        case null => None
        case row => Some(row)
      }
    }
  }
}
