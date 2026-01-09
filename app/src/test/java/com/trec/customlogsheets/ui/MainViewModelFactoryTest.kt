package com.trec.customlogsheets.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.trec.customlogsheets.data.AppDatabase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for MainViewModelFactory.
 * Tests ViewModel factory creation logic.
 */
class MainViewModelFactoryTest {
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockContext: Context
    private lateinit var factory: MainViewModelFactory

    @Before
    fun setUp() {
        mockDatabase = mock()
        mockContext = mock()
        factory = MainViewModelFactory(mockDatabase, mockContext)
    }

    @Test
    fun `create returns MainViewModel for correct class`() {
        val viewModel = factory.create(MainViewModel::class.java)

        assertNotNull(viewModel)
        assertTrue(viewModel is MainViewModel)
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

        assertTrue(viewModel is ViewModel)
    }

    // Helper test ViewModel class
    private class TestViewModel : ViewModel()
}
