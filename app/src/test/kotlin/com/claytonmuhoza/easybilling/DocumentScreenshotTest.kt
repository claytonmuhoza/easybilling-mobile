package com.claytonmuhoza.easybilling

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.documents.DocumentSettingsActions
import com.claytonmuhoza.easybilling.documents.DocumentSettingsProblem
import com.claytonmuhoza.easybilling.documents.DocumentSettingsScreen
import com.claytonmuhoza.easybilling.documents.DocumentSettingsUiState
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Screenshots of the settings of the documents, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class DocumentScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    /** A logo made for the tests: a green square with a white disc. */
    private fun logo(): DocumentLogo {
        val bitmap = Bitmap.createBitmap(LOGO_SIDE, LOGO_SIDE, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(0, 106, 96))
            drawCircle(LOGO_SIDE / 2f, LOGO_SIDE / 2f, LOGO_SIDE / 4f, Paint().apply { color = Color.WHITE })
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return DocumentLogo(png.toByteArray())
    }

    @Composable
    private fun screen(state: DocumentSettingsUiState) = DocumentSettingsScreen(state, DocumentSettingsActions())

    @Test
    fun documentSettingsDefault() = capture("document_settings") {
        screen(DocumentSettingsUiState.Ready(DocumentSettings()))
    }

    @Test
    fun documentSettingsCustomised() = capture("document_settings_customised") {
        val settings = DocumentSettings(footer = "Merci de votre visite", logo = logo(), showQrCode = true)
        screen(DocumentSettingsUiState.Ready(settings, saved = true))
    }

    @Test
    fun documentSettingsProblems() = capture("document_settings_problem") {
        val settings = DocumentSettings(footer = "x".repeat(DocumentSettings.MAX_FOOTER_LENGTH + 1))
        screen(DocumentSettingsUiState.Ready(settings, problem = DocumentSettingsProblem.FOOTER_TOO_LONG))
    }

    @Test
    fun documentSettingsNotAnImage() = capture("document_settings_not_an_image") {
        screen(DocumentSettingsUiState.Ready(DocumentSettings(), problem = DocumentSettingsProblem.NOT_AN_IMAGE))
    }

    @Test
    fun documentSettingsLoading() = capture("document_settings_loading", animated = true) {
        screen(DocumentSettingsUiState.Loading)
    }

    @Test
    fun documentSettingsError() = capture("document_settings_error") {
        screen(DocumentSettingsUiState.Failed)
    }

    /** The configurations of the screenshots. */
    companion object {
        private const val LOGO_SIDE = 160
        private const val PNG_QUALITY = 100

        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
