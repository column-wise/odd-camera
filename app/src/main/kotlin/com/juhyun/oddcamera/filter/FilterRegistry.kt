package com.juhyun.oddcamera.filter

import com.juhyun.oddcamera.filter.boxes.LightBoxesFilter
import com.juhyun.oddcamera.filter.none.NoneFilter

/** UI order. Light Boxes (Task 6) and ASCII (Task 7) are appended here. */
object FilterRegistry {
    val all: List<Filter> = listOf(NoneFilter, LightBoxesFilter)
}
