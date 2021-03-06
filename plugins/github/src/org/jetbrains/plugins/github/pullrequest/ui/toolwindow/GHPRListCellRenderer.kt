// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.ui.toolwindow

import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListUiUtil
import com.intellij.util.ui.UIUtil
import com.intellij.collaboration.ui.codereview.OpenReviewButton
import com.intellij.collaboration.ui.codereview.OpenReviewButtonViewModel
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestShort
import org.jetbrains.plugins.github.i18n.GithubBundle
import org.jetbrains.plugins.github.ui.avatars.GHAvatarIconsProvider
import org.jetbrains.plugins.github.ui.util.GHUIUtil
import java.awt.Component
import javax.swing.*

class GHPRListCellRenderer(private val avatarIconsProvider: GHAvatarIconsProvider,
                           private val openButtonViewModel: OpenReviewButtonViewModel)
  : ListCellRenderer<GHPullRequestShort>, JPanel() {

  private val stateIcon = JLabel()
  private val title = JLabel()
  private val info = JLabel()
  private val labels = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.X_AXIS)
  }
  private val assignees = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.X_AXIS)
  }
  private val openButtonPanel = OpenReviewButton.createOpenReviewButton(GithubBundle.message("pull.request.open.action"))

  init {
    val gapAfter = "${JBUI.scale(5)}px"

    val infoPanel = JPanel(null).apply {
      isOpaque = false
      border = JBUI.Borders.empty(5, 8)

      layout = MigLayout(LC().gridGap(gapAfter, "0")
                           .insets("0", "0", "0", "0")
                           .fillX())

      add(stateIcon)

      add(title, CC()
        .minWidth("pref/2px")
        .pushX()
        .split(2)
        .shrinkPrioX(1))
      add(labels, CC()
        .minWidth("0px")
        .pushX()
        .shrinkPrioX(0))

      add(info, CC().newline()
        .minWidth("0px")
        .pushX()
        .skip(1))
    }


    layout = MigLayout(LC().gridGap(gapAfter, "0").noGrid()
                         .insets("0", "0", "0", "0")
                         .fillX())

    add(infoPanel, CC().minWidth("0"))
    add(assignees, CC().minWidth("0").gapBefore("push"))
    add(openButtonPanel, CC().minWidth("pref").growY())
  }

  override fun getListCellRendererComponent(list: JList<out GHPullRequestShort>,
                                            value: GHPullRequestShort,
                                            index: Int,
                                            isSelected: Boolean,
                                            cellHasFocus: Boolean): Component {
    background = ListUiUtil.WithTallRow.background(list, isSelected, list.hasFocus())
    val primaryTextColor = ListUiUtil.WithTallRow.foreground(isSelected, list.hasFocus())
    val secondaryTextColor = ListUiUtil.WithTallRow.secondaryForeground(list, isSelected)

    stateIcon.apply {
      icon = GHUIUtil.getPullRequestStateIcon(value.state, value.isDraft)
      toolTipText = GHUIUtil.getPullRequestStateText(value.state, value.isDraft)
    }
    title.apply {
      text = value.title
      foreground = primaryTextColor
    }
    info.apply {
      text = GithubBundle.message("pull.request.list.item.info", value.number, value.author?.login,
                                  DateFormatUtil.formatDate(value.createdAt))
      foreground = secondaryTextColor
    }
    labels.apply {
      removeAll()
      for (label in value.labels) {
        add(GHUIUtil.createIssueLabelLabel(label))
        add(Box.createRigidArea(JBDimension(4, 0)))
      }
    }
    assignees.apply {
      removeAll()
      for (assignee in value.assignees) {
        if (componentCount != 0) {
          add(Box.createRigidArea(JBDimension(UIUtil.DEFAULT_HGAP, 0)))
        }
        add(JLabel().apply {
          icon = avatarIconsProvider.getIcon(assignee.avatarUrl)
          toolTipText = assignee.login
        })
      }
    }
    openButtonPanel.apply {
      isVisible = index == openButtonViewModel.hoveredRowIndex
      isOpaque = openButtonViewModel.isButtonHovered
    }

    return this
  }
}
