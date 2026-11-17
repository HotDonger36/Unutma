package com.example.unutma.widget

import android.widget.RemoteViews
import com.example.unutma.util.Lang

/** Like setTextViewText, but plain strings are shown in the chosen language (see [Lang.tr]). */
fun RemoteViews.text(viewId: Int, s: CharSequence) = setTextViewText(viewId, if (s is String) Lang.tr(s) else s)
