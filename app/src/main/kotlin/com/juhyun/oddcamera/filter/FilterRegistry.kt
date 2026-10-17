package com.juhyun.oddcamera.filter

import com.juhyun.oddcamera.filter.ascii.AsciiFilter
import com.juhyun.oddcamera.filter.boxes.LightBoxesFilter
import com.juhyun.oddcamera.filter.none.NoneFilter

/** UI order. */
object FilterRegistry {
    val all: List<Filter> = listOf(NoneFilter, LightBoxesFilter, AsciiFilter)
}
