package build.wallet.notifications

import build.wallet.database.BitkeyDatabaseProvider
import build.wallet.queueprocessor.Queue
import build.wallet.sqldelight.awaitAsListResult
import build.wallet.sqldelight.awaitTransaction
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class RegisterWatchAddressQueueImpl(
  private val databaseProvider: BitkeyDatabaseProvider,
) : Queue<RegisterWatchAddressContext> {
  override suspend fun append(item: RegisterWatchAddressContext): Result<Unit, Error> {
    return databaseProvider.database().awaitTransaction {
      registerWatchAddressQueueQueries.append(
        item.address,
        item.spendingKeysetId,
        item.accountId,
        item.f8eEnvironment
      )
    }
  }

  override suspend fun take(num: Int): Result<List<RegisterWatchAddressContext>, Error> {
    require(num >= 0) { "Requested element count $num is less than zero." }

    return databaseProvider.database()
      .registerWatchAddressQueueQueries.take(num.toLong())
      .awaitAsListResult()
      .map { items ->
        items.map {
          RegisterWatchAddressContext(
            it.address,
            it.spendingKeysetId,
            it.accountId,
            it.f8eEnvironment
          )
        }
      }
  }

  override suspend fun removeFirst(num: Int): Result<Unit, Error> {
    require(num >= 0) { "Requested element count $num is less than zero." }

    return databaseProvider.database().awaitTransaction {
      registerWatchAddressQueueQueries.removeFirst(num.toLong())
    }
  }
}
