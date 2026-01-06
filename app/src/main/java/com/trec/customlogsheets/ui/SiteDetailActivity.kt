package com.trec.customlogsheets.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SamplingSite

class SiteDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_detail)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        val site = intent.getParcelableExtra<SamplingSite>("site")
        site?.let {
            val siteNameText = findViewById<TextView>(R.id.textViewSiteName)
            siteNameText.text = it.name
            supportActionBar?.title = it.name
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

