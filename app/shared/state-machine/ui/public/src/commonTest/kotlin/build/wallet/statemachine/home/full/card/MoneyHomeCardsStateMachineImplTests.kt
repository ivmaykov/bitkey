package build.wallet.statemachine.home.full.card

import build.wallet.availability.AppFunctionalityStatus
import build.wallet.compose.collections.emptyImmutableList
import build.wallet.compose.collections.immutableListOf
import build.wallet.f8e.socrec.SocRecRelationships
import build.wallet.statemachine.StateMachineMock
import build.wallet.statemachine.core.LabelModel
import build.wallet.statemachine.core.test
import build.wallet.statemachine.data.firmware.FirmwareDataUpToDateMock
import build.wallet.statemachine.data.keybox.ActiveKeyboxLoadedDataMock
import build.wallet.statemachine.data.recovery.losthardware.LostHardwareRecoveryData.InitiatingLostHardwareRecoveryData.AwaitingNewHardwareData
import build.wallet.statemachine.moneyhome.card.CardModel
import build.wallet.statemachine.moneyhome.card.MoneyHomeCardsProps
import build.wallet.statemachine.moneyhome.card.MoneyHomeCardsUiStateMachineImpl
import build.wallet.statemachine.moneyhome.card.backup.CloudBackupHealthCardUiProps
import build.wallet.statemachine.moneyhome.card.backup.CloudBackupHealthCardUiStateMachine
import build.wallet.statemachine.moneyhome.card.fwup.DeviceUpdateCardUiProps
import build.wallet.statemachine.moneyhome.card.fwup.DeviceUpdateCardUiStateMachine
import build.wallet.statemachine.moneyhome.card.gettingstarted.GettingStartedCardUiProps
import build.wallet.statemachine.moneyhome.card.gettingstarted.GettingStartedCardUiStateMachine
import build.wallet.statemachine.moneyhome.card.replacehardware.ReplaceHardwareCardUiProps
import build.wallet.statemachine.moneyhome.card.replacehardware.ReplaceHardwareCardUiStateMachine
import build.wallet.statemachine.recovery.hardware.HardwareRecoveryStatusCardUiProps
import build.wallet.statemachine.recovery.hardware.HardwareRecoveryStatusCardUiStateMachine
import build.wallet.statemachine.recovery.socrec.RecoveryContactCardsUiProps
import build.wallet.statemachine.recovery.socrec.RecoveryContactCardsUiStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.ImmutableList

class MoneyHomeCardsStateMachineImplTests : FunSpec({
  val deviceUpdateCardUiStateMachine =
    object : DeviceUpdateCardUiStateMachine, StateMachineMock<DeviceUpdateCardUiProps, CardModel?>(
      initialModel = null
    ) {}
  val gettingStartedCardStateMachine =
    object : GettingStartedCardUiStateMachine, StateMachineMock<GettingStartedCardUiProps, CardModel?>(
      initialModel = null
    ) {}
  val hardwareRecoveryStatusCardUiStateMachine =
    object : HardwareRecoveryStatusCardUiStateMachine, StateMachineMock<HardwareRecoveryStatusCardUiProps, CardModel?>(
      initialModel = null
    ) {}
  val recoveryContactCardsUiStateMachine =
    object : RecoveryContactCardsUiStateMachine, StateMachineMock<RecoveryContactCardsUiProps, ImmutableList<CardModel>>(
      initialModel = emptyImmutableList()
    ) {}
  val replaceHardwareCardUiStateMachine =
    object : ReplaceHardwareCardUiStateMachine, StateMachineMock<ReplaceHardwareCardUiProps, CardModel?>(
      initialModel = null
    ) {}
  val cloudBackupHealthCardUiStateMachine =
    object : CloudBackupHealthCardUiStateMachine, StateMachineMock<CloudBackupHealthCardUiProps, CardModel?>(
      initialModel = null
    ) {}

  val stateMachine =
    MoneyHomeCardsUiStateMachineImpl(
      deviceUpdateCardUiStateMachine = deviceUpdateCardUiStateMachine,
      gettingStartedCardUiStateMachine = gettingStartedCardStateMachine,
      hardwareRecoveryStatusCardUiStateMachine = hardwareRecoveryStatusCardUiStateMachine,
      recoveryContactCardsUiStateMachine = recoveryContactCardsUiStateMachine,
      replaceHardwareCardUiStateMachine = replaceHardwareCardUiStateMachine,
      cloudBackupHealthCardUiStateMachine = cloudBackupHealthCardUiStateMachine
    )

  val props =
    MoneyHomeCardsProps(
      deviceUpdateCardUiProps =
        DeviceUpdateCardUiProps(
          firmwareData = FirmwareDataUpToDateMock,
          onUpdateDevice = {}
        ),
      gettingStartedCardUiProps =
        GettingStartedCardUiProps(
          accountData = ActiveKeyboxLoadedDataMock,
          appFunctionalityStatus = AppFunctionalityStatus.FullFunctionality,
          trustedContacts = emptyList(),
          onAddBitcoin = {},
          onEnableSpendingLimit = {},
          onInviteTrustedContact = {},
          onShowAlert = {},
          onDismissAlert = {}
        ),
      hardwareRecoveryStatusCardUiProps =
        HardwareRecoveryStatusCardUiProps(
          lostHardwareRecoveryData =
            AwaitingNewHardwareData(
              addHardwareKeys = { _, _ -> }
            ),
          onClick = {}
        ),
      recoveryContactCardsUiProps =
        RecoveryContactCardsUiProps(
          relationships = SocRecRelationships.EMPTY,
          onClick = {}
        ),
      replaceHardwareCardUiProps =
        ReplaceHardwareCardUiProps(
          onReplaceDevice = {}
        ),
      cloudBackupHealthCardUiProps = CloudBackupHealthCardUiProps(onActionClick = {})
    )

  afterTest {
    deviceUpdateCardUiStateMachine.reset()
    gettingStartedCardStateMachine.reset()
    recoveryContactCardsUiStateMachine.reset()
  }

  test("card list should be empty") {
    stateMachine.test(props) {
      awaitItem().cards.shouldBeEmpty()
    }
  }

  test("card list should have length 1 when there is a getting started card") {
    gettingStartedCardStateMachine.emitModel(TEST_CARD_MODEL)
    stateMachine.test(props) {
      awaitItem().cards.size.shouldBe(1)
    }
  }

  test("card list should have length 1 when there is a device update card") {
    deviceUpdateCardUiStateMachine.emitModel(TEST_CARD_MODEL)
    stateMachine.test(props) {
      awaitItem().cards.size.shouldBe(1)
    }
  }

  test("card list should have length 1 when there is a hw status card") {
    hardwareRecoveryStatusCardUiStateMachine.emitModel(TEST_CARD_MODEL)
    stateMachine.test(props) {
      awaitItem().cards.size.shouldBe(1)
    }
  }

  test("card list should include invitation cards in the middle") {
    hardwareRecoveryStatusCardUiStateMachine.emitModel(TEST_CARD_MODEL)
    recoveryContactCardsUiStateMachine.emitModel(
      immutableListOf(
        TEST_CARD_MODEL.copy(subtitle = "first invitation"),
        TEST_CARD_MODEL.copy(subtitle = "second invitation"),
        TEST_CARD_MODEL.copy(subtitle = "third invitation")
      )
    )
    gettingStartedCardStateMachine.emitModel(TEST_CARD_MODEL)
    stateMachine.test(props) {
      awaitItem().cards.let {
        it.size.shouldBe(5)
        it[0].subtitle.shouldBeNull()
        it[1].subtitle.shouldBe("first invitation")
        it[2].subtitle.shouldBe("second invitation")
        it[3].subtitle.shouldBe("third invitation")
        it[4].subtitle.shouldBeNull()
      }
    }
  }

  test(
    "card list should have length 1 when there is both a hw status card and a device update card and a replace hardware card"
  ) {
    deviceUpdateCardUiStateMachine.emitModel(TEST_CARD_MODEL)
    hardwareRecoveryStatusCardUiStateMachine.emitModel(
      TEST_CARD_MODEL.copy(
        title =
          LabelModel.StringWithStyledSubstringModel.from(
            "HW CARD",
            emptyMap()
          )
      )
    )
    replaceHardwareCardUiStateMachine.emitModel(
      TEST_CARD_MODEL.copy(
        title =
          LabelModel.StringWithStyledSubstringModel.from(
            "REPLACE HW CARD",
            emptyMap()
          )
      )
    )
    stateMachine.test(props) {
      with(awaitItem()) {
        cards.size.shouldBe(1)
        cards.first().title.string.shouldBe("HW CARD")
      }
    }
  }
})

val TEST_CARD_MODEL =
  CardModel(
    title =
      LabelModel.StringWithStyledSubstringModel.from(
        "Test Card",
        emptyMap()
      ),
    subtitle = null,
    leadingImage = null,
    content = null,
    style = CardModel.CardStyle.Outline
  )
