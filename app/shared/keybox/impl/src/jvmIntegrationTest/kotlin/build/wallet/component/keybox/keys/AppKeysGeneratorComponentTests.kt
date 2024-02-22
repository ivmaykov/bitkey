package build.wallet.component.keybox.keys

import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitcoin.BitcoinNetworkType.BITCOIN
import build.wallet.testing.launchNewApp
import build.wallet.testing.shouldBeOk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.enum

class AppKeysGeneratorComponentTests : FunSpec({
  val app = launchNewApp().app

  test("KeyBundle uses random localId") {
    val appKeyBundle1 =
      app.appKeysGenerator
        .generateKeyBundle(network = BITCOIN)
        .shouldBeOk()

    val appKeyBundle2 =
      app.appKeysGenerator
        .generateKeyBundle(network = BITCOIN)
        .shouldBeOk()

    appKeyBundle1.localId.shouldNotBeNull()
    appKeyBundle2.localId.shouldNotBeNull()
    appKeyBundle1.localId.shouldNotBe(appKeyBundle2.localId)
  }

  test("generate new app KeyBundle") {
    checkAll(Exhaustive.enum<BitcoinNetworkType>()) { network ->
      val appKeyBundle =
        app.appKeysGenerator
          .generateKeyBundle(network)
          .shouldBeOk()

      appKeyBundle.networkType.shouldBe(network)

      app.appComponent.appPrivateKeyDao
        .getAppSpendingPrivateKey(appKeyBundle.spendingKey)
        .shouldBeOk()
        .shouldNotBeNull()

      app.appComponent.appPrivateKeyDao
        .getGlobalAuthKey(appKeyBundle.authKey)
        .shouldBeOk()
        .shouldNotBeNull()

      app.appComponent.appPrivateKeyDao
        .getRecoveryAuthKey(appKeyBundle.recoveryAuthKey.shouldNotBeNull())
        .shouldBeOk()
        .shouldNotBeNull()
    }
  }
})