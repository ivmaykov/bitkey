@file:JvmName("DataRowItemKt")

package build.wallet.ui.data

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import build.wallet.statemachine.core.Icon.SmallIconCaretRight
import build.wallet.statemachine.core.form.FormMainContentModel.DataList.Data
import build.wallet.statemachine.core.form.FormMainContentModel.DataList.Data.SideTextTreatment
import build.wallet.statemachine.core.form.FormMainContentModel.DataList.Data.SideTextType
import build.wallet.ui.components.icon.Icon
import build.wallet.ui.components.icon.IconImage
import build.wallet.ui.components.icon.iconStyle
import build.wallet.ui.components.label.Label
import build.wallet.ui.components.label.LabelTreatment
import build.wallet.ui.components.layout.Divider
import build.wallet.ui.compose.thenIf
import build.wallet.ui.model.icon.IconSize.Small
import build.wallet.ui.theme.WalletTheme
import build.wallet.ui.tokens.LabelType
import build.wallet.ui.tooling.PreviewWalletTheme

@Composable
internal fun DataRowRegular(
  modifier: Modifier = Modifier,
  model: Data,
  isFirst: Boolean,
) {
  DataRowRegular(
    modifier =
      modifier
        .padding(
          start = 16.dp,
          top =
            if (isFirst) {
              16.dp
            } else {
              0.dp
            },
          end = 16.dp
        )
        .thenIf(model.onClick != null) {
          Modifier.clickable {
            model.onClick?.invoke()
          }
        },
    showBottomDivider = model.showBottomDivider,
    leadingContent = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.thenIf(model.onTitle != null) {
            Modifier.clickable {
              model.onTitle?.invoke()
            }
          }
      ) {
        Label(
          text = model.title,
          type = LabelType.Body3Regular,
          alignment = TextAlign.Start,
          treatment = LabelTreatment.Secondary
        )
        model.titleIcon?.let { titleIcon ->
          IconImage(
            modifier = Modifier.padding(start = 4.dp),
            model = titleIcon,
            style =
              WalletTheme.iconStyle(
                icon = titleIcon.iconImage,
                color = Color.Unspecified,
                tint = titleIcon.iconTint
              )
          )
        }
      }
    },
    trailingContent = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.End
        ) {
          Label(
            text = model.sideText,
            alignment = TextAlign.End,
            type = model.sideTextType.toLabelType(),
            treatment = model.sideTextTreatment.toLabelTreatment()
          )
          model.secondarySideText?.let {
            Label(
              text = it,
              type = model.secondarySideTextType.toLabelType(),
              treatment = model.secondarySideTextTreatment.toLabelTreatment(),
              alignment = TextAlign.End
            )
          }
        }
        model.onClick?.let {
          Icon(icon = SmallIconCaretRight, size = Small, color = WalletTheme.colors.foreground30)
        }
      }
    },
    helperContent =
      when (val explainer = model.explainer) {
        null -> null
        else -> {
          {
            Column(
              modifier =
                Modifier
                  .background(color = WalletTheme.colors.foreground10)
                  .padding(16.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.Start
            ) {
              Label(
                text = explainer.title,
                type = LabelType.Body3Bold,
                alignment = TextAlign.Start,
                treatment = LabelTreatment.Primary
              )
              Spacer(Modifier.height(6.dp))
              Label(
                text = explainer.subtitle,
                type = LabelType.Body3Regular,
                alignment = TextAlign.Start,
                treatment = LabelTreatment.Secondary
              )
            }
          }
        }
      }
  )
}

private fun SideTextType.toLabelType(): LabelType {
  return when (this) {
    SideTextType.REGULAR -> LabelType.Body3Regular
    SideTextType.MEDIUM -> LabelType.Body3Medium
    SideTextType.BOLD -> LabelType.Body3Bold
    SideTextType.BODY2BOLD -> LabelType.Body2Bold
  }
}

private fun SideTextTreatment.toLabelTreatment(): LabelTreatment {
  return when (this) {
    SideTextTreatment.PRIMARY -> LabelTreatment.Primary
    SideTextTreatment.SECONDARY -> LabelTreatment.Secondary
    SideTextTreatment.WARNING -> LabelTreatment.Warning
    SideTextTreatment.STRIKETHROUGH -> LabelTreatment.Strikethrough
  }
}

@Composable
internal fun DataRowRegular(
  modifier: Modifier = Modifier,
  showBottomDivider: Boolean,
  leadingContent: @Composable () -> Unit,
  trailingContent: @Composable () -> Unit,
  helperContent: (@Composable () -> Unit)?,
) {
  val lineColor = Color.Black.copy(alpha = 0.05F)

  Column {
    Row(
      modifier =
        modifier
          .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      leadingContent()
      Spacer(Modifier.width(8.dp))
      trailingContent()
    }

    helperContent?.let {
      Spacer(Modifier.height(16.dp))
      it()
    }

    if (showBottomDivider) {
      Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = lineColor)
    } else if (helperContent == null) {
      // Only add spacer if we do not have a helper content.
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Preview
@Composable
private fun DataRowPreview() {
  PreviewWalletTheme {
    DataRowRegular(
      isFirst = false,
      model =
        Data(
          title = "Miner Fee",
          sideText = "bc1q...xyB1",
          sideTextTreatment = SideTextTreatment.PRIMARY
        )
    )
  }
}

@Preview
@Composable
private fun DataRowWithSecondaryPreview() {
  PreviewWalletTheme {
    DataRowRegular(
      isFirst = false,
      model =
        Data(
          title = "Miner Fee",
          sideText = "bc1q...xyB1",
          secondarySideText = "bc1q...xyB1",
          sideTextTreatment = SideTextTreatment.PRIMARY
        )
    )
  }
}
