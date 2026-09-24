package kr.mom.probe.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import kr.mom.probe.MainActivity
import kr.mom.probe.R

/** A private, static entry point: no family or notification content is exposed on the launcher. */
class AssistantWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        update(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val EXTRA_OPEN_ASSISTANT = "kr.mom.probe.widget.OPEN_ASSISTANT"

        /** True means the launcher accepted the request, not that the user placed the widget. */
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(
                ComponentName(context, AssistantWidgetProvider::class.java), null, null,
            )
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_ASSISTANT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(
                context, 4200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.assistant_widget_layout).apply {
                setOnClickPendingIntent(R.id.assistant_widget_root, open)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
