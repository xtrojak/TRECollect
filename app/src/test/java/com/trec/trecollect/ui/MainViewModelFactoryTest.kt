package com.trec.trecollect.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.trec.trecollect.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for MainViewModelFactory.
 * Tests ViewModel factory creation logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelFactoryTest {
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockContext: Context
    private lateinit var factory: MainViewModelFactory

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockDatabase = mock()
        mockContext = mock()
        factory = MainViewModelFactory(mockDatabase, mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create returns MainViewModel for correct class`() {
        val viewModel = factory.create(MainViewModel::class.java)
        assertNotNull(viewModel)
    }

    @Test
    fun `create throws IllegalArgumentException for unknown class`() {
        try {
            factory.create(TestViewModel::class.java)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unknown ViewModel class", e.message)
        }
    }

    @Test
    fun `create handles ViewModel superclass`() {
        val viewModel = factory.create(MainViewModel::class.java)
        val asViewModel: ViewModel = viewModel
        assertNotNull(asViewModel)
    }

    // Helper test ViewModel class
    private class TestViewModel : ViewModel()
}
