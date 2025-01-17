package build.wallet.sqldelight

import app.cash.turbine.test
import build.wallet.LoadableValue.InitialLoading
import build.wallet.LoadableValue.LoadedValue
import build.wallet.db.DbQueryError
import build.wallet.sqldelight.ThrowingSqlDriver.QUERY_ERROR
import build.wallet.sqldelight.dummy.DummyDataEntity
import build.wallet.sqldelight.dummy.DummyDatabase
import build.wallet.testing.shouldBeErr
import build.wallet.testing.shouldBeOk
import io.kotest.core.spec.style.FunSpec

class FlowsTests : FunSpec({
  val sqlDriverFactory = inMemorySqlDriver().factory

  val sqlDriver =
    sqlDriverFactory.createDriver(
      dataBaseName = "foods.db",
      dataBaseSchema = DummyDatabase.Schema
    )
  val testDataQueries = DummyDatabase(sqlDriver).dummyDataQueries

  afterTest {
    testDataQueries.clear()
  }

  context("asFlowOfList") {
    test("single item - ok") {
      testDataQueries.insertDummyData(1, "chocolate")

      testDataQueries.getDummyData()
        .asFlowOfList()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(LoadedValue(listOf(DummyDataEntity(id = 1, value = "chocolate"))))
        }
    }

    test("many items - ok") {
      testDataQueries.insertDummyData(1, "chocolate")
      testDataQueries.insertDummyData(2, "croissant")

      testDataQueries.getDummyData()
        .asFlowOfList()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(
            LoadedValue(
              listOf(
                DummyDataEntity(1, "chocolate"),
                DummyDataEntity(2, "croissant")
              )
            )
          )
        }
    }

    test("no items - ok") {
      testDataQueries.getDummyData()
        .asFlowOfList()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(LoadedValue(emptyList()))
        }
    }

    test("query error") {
      testDataQueries.getDummyData(throwError = true)
        .asFlowOfList()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(DbQueryError(QUERY_ERROR))
          awaitComplete() // Error is terminal
        }
    }
  }

  context("asFlowOfOneOrNull") {
    test("single item - ok") {
      testDataQueries.insertDummyData(1, "chocolate")

      testDataQueries.getDummyData()
        .asFlowOfOneOrNull()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(LoadedValue(DummyDataEntity(id = 1, value = "chocolate")))
        }
    }

    test("many items - error") {
      testDataQueries.insertDummyData(1, "chocolate")
      testDataQueries.insertDummyData(2, "croissant")

      testDataQueries.getDummyData()
        .asFlowOfOneOrNull()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(
            DbQueryError(
              IllegalStateException("ResultSet returned more than 1 row for dummy:queryDummyData")
            )
          )
          awaitComplete() // Error is terminal
        }
    }

    test("no items - ok") {
      testDataQueries.getDummyData()
        .asFlowOfOneOrNull()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(LoadedValue(null))
        }
    }

    test("query error") {
      testDataQueries.getDummyData(throwError = true)
        .asFlowOfOneOrNull()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(DbQueryError(QUERY_ERROR))
          awaitComplete() // Error is terminal
        }
    }
  }

  context("asFlowOfOne") {
    test("single item - ok") {
      testDataQueries.insertDummyData(1, "chocolate")

      testDataQueries.getDummyData()
        .asFlowOfOne()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeOk(LoadedValue(DummyDataEntity(id = 1, value = "chocolate")))
        }
    }

    test("many items - error") {
      testDataQueries.insertDummyData(1, "chocolate")
      testDataQueries.insertDummyData(2, "croissant")

      testDataQueries.getDummyData()
        .asFlowOfOne()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(
            DbQueryError(
              IllegalStateException("ResultSet returned more than 1 row for dummy:queryDummyData")
            )
          )
          awaitComplete() // Error is terminal
        }
    }

    test("no items - error") {
      testDataQueries.getDummyData()
        .asFlowOfOne()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(
            DbQueryError(
              NullPointerException("ResultSet returned null for dummy:queryDummyData")
            )
          )
          awaitComplete() // Error is terminal
        }
    }

    test("query error") {
      testDataQueries.getDummyData(throwError = true)
        .asFlowOfOne()
        .test {
          awaitItem().shouldBeOk(InitialLoading)
          awaitItem().shouldBeErr(DbQueryError(QUERY_ERROR))
          awaitComplete() // Error is terminal
        }
    }
  }
})
