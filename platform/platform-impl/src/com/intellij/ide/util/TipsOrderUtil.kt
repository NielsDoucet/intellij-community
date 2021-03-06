// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.internal.statistic.eventLog.EventLogConfiguration
import com.intellij.internal.statistic.local.ActionSummary
import com.intellij.internal.statistic.local.ActionsLocalSummary
import com.intellij.internal.statistic.utils.StatisticsUploadAssistant
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.util.PlatformUtils
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<TipsOrderUtil>()
private const val RANDOM_SHUFFLE_ALGORITHM = "default_shuffle"
private const val TIPS_SERVER_URL = "https://feature-recommendation.analytics.aws.intellij.net/tips/v1"

internal data class RecommendationDescription(val algorithm: String, val tips: List<TipAndTrickBean>, val version: String?)

@Service
internal class TipsOrderUtil {
  private class RecommendationsStartupActivity : StartupActivity.Background {
    private val scheduledFuture = AtomicReference<ScheduledFuture<*>>()

    override fun runActivity(project: Project) {
      val app = ApplicationManager.getApplication()
      if (!app.isEAP || app.isHeadlessEnvironment || !StatisticsUploadAssistant.isSendAllowed()) {
        return
      }

      scheduledFuture.getAndSet(AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable {
        try {
          sync()
        }
        finally {
          scheduledFuture.getAndSet(AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable(::sync), 3, TimeUnit.HOURS))
            ?.cancel(false)
        }
      }, 5, TimeUnit.MILLISECONDS))
        ?.cancel(false)
    }
  }

  companion object {
    private fun sync() {
      LOG.assertTrue(!ApplicationManager.getApplication().isDispatchThread)
      LOG.debug { "Fetching tips order from the server: ${TIPS_SERVER_URL}" }
      val allTips = TipAndTrickBean.EP_NAME.iterable.map { it.fileName }
      val actionsSummary = service<ActionsLocalSummary>().getActionsStats()
      val startTimestamp = System.currentTimeMillis()
      HttpRequests.post(TIPS_SERVER_URL, HttpRequests.JSON_CONTENT_TYPE)
        .connect(HttpRequests.RequestProcessor { request ->
          val bucket = EventLogConfiguration.getInstance().bucket
          val tipsRequest = TipsRequest(allTips, actionsSummary, PlatformUtils.getPlatformPrefix(), bucket)
          val objectMapper = ObjectMapper()
          request.write(objectMapper.writeValueAsBytes(tipsRequest))
          val recommendation = objectMapper.readValue(request.readString(), ServerRecommendation::class.java)

          LOG.debug {
            val duration = System.currentTimeMillis() - startTimestamp
            val algorithmInfo = "${recommendation.usedAlgorithm}:${recommendation.version}"
            "Server recommendation made. Algorithm: $algorithmInfo. Duration: ${duration}"
          }

          service<TipsOrderUtil>().serverRecommendation = recommendation
        }, null, LOG)
    }
  }

  @Volatile
  private var serverRecommendation: ServerRecommendation? = null

  /**
   * Reorders tips to show the most useful ones in the beginning
   *
   * @return object that contains sorted tips and describes approach of how the tips are sorted
   */
  fun sort(tips: List<TipAndTrickBean>): RecommendationDescription {
    // temporarily suggest random order if we cannot estimate quality
    return serverRecommendation?.reorder(tips)
           ?: RecommendationDescription(RANDOM_SHUFFLE_ALGORITHM, tips.shuffled(), null)
  }
}

private data class TipsRequest(
  val tips: List<String>,
  val usageInfo: Map<String, ActionSummary>,
  val ideName: String, // product code
  val bucket: Int
)

private class ServerRecommendation {
  @JvmField
  var showingOrder = emptyList<String>()

  @JvmField
  var usedAlgorithm = "unknown"

  @JvmField
  var version: String? = null

  fun reorder(tips: List<TipAndTrickBean>): RecommendationDescription? {
    val tipToIndex = Object2IntOpenHashMap<String>(showingOrder.size)
    showingOrder.forEachIndexed { index, tipFile -> tipToIndex.put(tipFile, index) }
    for (tip in tips) {
      if (!tipToIndex.containsKey(tip.fileName)) {
        LOG.error("Unknown tips file: ${tip.fileName}")
        return null
      }
    }
    return RecommendationDescription(usedAlgorithm, tips.sortedBy { tipToIndex.getInt(it.fileName) }, version)
  }
}