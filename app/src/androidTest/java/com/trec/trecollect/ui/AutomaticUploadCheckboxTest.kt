package com.trec.trecollect.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trec.trecollect.MainActivity
import com.trec.trecollect.data.AppDatabase
import com.trec.trecollect.data.SamplingSite
import com.trec.trecollect.data.SiteStatus
import com.trec.trecollect.data.UploadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test that verifies: when upload "success" is simulated from a background
 * scope (like automatic upload after finalize), the finishedSites flow emits the site
 * with UPLOADED so the checkbox would update. This reproduces the bug where the
 * checkbox did not tick automatically after auto-upload.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticUploadCheckboxTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.trec.trecollect.data.SettingsPreferences(context)
        settings.setSamplingTeam("LSI")
        settings.setSamplingSubteam("Soil")
        settings.setFolderUri("content://test/dummy")
    }

    @Test
    fun whenUploadSuccessSimulatedFromBackground_finishedSitesEmitsSiteWithUploaded() = runBlocking {
        lateinit var viewModel: MainViewModel
        lateinit var database: AppDatabase

        activityRule.scenario.onActivity { activity ->
            database = AppDatabase.getDatabase(activity.applicationContext)
            viewModel = androidx.lifecycle.ViewModelProvider(
                activity,
                MainViewModelFactory(database, activity.applicationContext)
            )[MainViewModel::class.java]
        }

        val siteName = "TestSite_${System.currentTimeMillis()}"
        val site = SamplingSite(
            id = 0,
            name = siteName,
            status = SiteStatus.FINISHED,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            createdAt = System.currentTimeMillis()
        )

        database.samplingSiteDao().insertSite(site)
        val inserted = database.samplingSiteDao().getSiteByName(siteName)
        assertNotNull(inserted)
        val siteWithId = inserted!!

        viewModel.testOnlySetFinishedSites(listOf(siteWithId.copy(uploadStatus = UploadStatus.NOT_UPLOADED)))
        viewModel.testOnlySimulateUploadSuccessFromBackground(siteWithId)

        val timeoutMs = 5000L
        val intervalMs = 50L
        val start = System.currentTimeMillis()
        var listWithUploaded: List<SamplingSite>? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            val list = viewModel.finishedSites.first()
            if (list.any { it.name == siteName && it.uploadStatus == UploadStatus.UPLOADED }) {
                listWithUploaded = list
                break
            }
            delay(intervalMs)
        }

        assertNotNull(
            "finishedSites should eventually contain $siteName with UPLOADED within ${timeoutMs}ms",
            listWithUploaded
        )
        val updatedSite = listWithUploaded!!.find { it.name == siteName }
        assertNotNull("Site $siteName should be in finished list", updatedSite)
        assertEquals(
            "Site should have UPLOADED status so checkbox would be checked",
            UploadStatus.UPLOADED,
            updatedSite!!.uploadStatus
        )
    }
}
