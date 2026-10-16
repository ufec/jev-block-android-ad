package me.ethanxu.jevnoisegate.feature.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationExtractorTest {
    @Test fun `messaging style exposes complete message text`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val sender = Person.Builder().setName("Bank").build()
        val note = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.MessagingStyle(sender).addMessage("验证码 123456", 1L, sender)).build()
        assertTrue(NotificationExtractor.bodyOf(note).contains("验证码 123456"))
    }
    @Test fun `big text retains content outside folded text`() {
        val note = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "完整正文验证码123456")
            extras.putCharSequence(Notification.EXTRA_TEXT, "完整正文")
        }
        assertTrue(NotificationExtractor.bodyOf(note).contains("验证码123456"))
    }
}
