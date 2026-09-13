package ai.opencode.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationTest {

    @Test
    fun applicationIdIsSet() {
        assertTrue(BuildConfig.APPLICATION_ID.contains("ai.opencode.mobile"))
    }
}
