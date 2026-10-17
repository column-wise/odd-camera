package com.juhyun.oddcamera

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.ui.PermissionGate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionDeniedTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun deniedShowsRationaleAndSettingsButton() {
        var settingsOpened = false
        rule.setContent {
            PermissionGate(granted = false, onRequest = {}, onOpenSettings = { settingsOpened = true }) {
                Text("camera content")
            }
        }
        rule.onNodeWithText("카메라 권한이 필요합니다").assertIsDisplayed()
        rule.onNodeWithText("설정 열기").assertIsDisplayed().performClick()
        rule.onNodeWithText("camera content").assertDoesNotExist()
        assertTrue(settingsOpened)
    }

    @Test
    fun grantedShowsContent() {
        rule.setContent {
            PermissionGate(granted = true, onRequest = {}, onOpenSettings = {}) { Text("camera content") }
        }
        rule.onNodeWithText("camera content").assertIsDisplayed()
        rule.onNodeWithText("카메라 권한이 필요합니다").assertDoesNotExist()
    }
}
