package com.trec.trecollect.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringWriter

/**
 * Metadata for a site, stored in site_metadata.xml
 * Contains information about when the site was created, what config versions were used, etc.
 */
data class SiteMetadata(
    val siteName: String,
    val createdAt: String, // ISO 8601 UTC timestamp
    val teamConfigId: String = "", // Team config folder ID (should always be set for normal sites)
    val teamConfigVersion: String = "", // Team config version e.g. "1.0.0" (should always be set for normal sites)
    val submittedAt: String? = null, // ISO 8601 UTC timestamp when submitted
    val deletedAt: String? = null, // ISO 8601 UTC timestamp when deleted
    val uploadedAt: String? = null // ISO 8601 UTC timestamp when uploaded (persisted so status survives DB rebuild)
) {
    companion object {
        /**
         * Gets current timestamp in ISO 8601 UTC format
         */
        fun getCurrentTimestamp(): String {
            return java.time.Instant.now().toString()
        }
        
        /**
         * Serializes site metadata to XML
         */
        fun toXml(metadata: SiteMetadata): String {
            val serializer = Xml.newSerializer()
            val writer = StringWriter()
            
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "siteMetadata")
            
            serializer.startTag(null, "siteName")
            serializer.text(metadata.siteName)
            serializer.endTag(null, "siteName")
            
            serializer.startTag(null, "createdAt")
            serializer.text(metadata.createdAt)
            serializer.endTag(null, "createdAt")
            
            if (metadata.teamConfigId.isNotEmpty()) {
                serializer.startTag(null, "teamConfigId")
                serializer.text(metadata.teamConfigId)
                serializer.endTag(null, "teamConfigId")
            }
            
            if (metadata.teamConfigVersion.isNotEmpty()) {
                serializer.startTag(null, "teamConfigVersion")
                serializer.text(metadata.teamConfigVersion)
                serializer.endTag(null, "teamConfigVersion")
            }
            
            if (metadata.submittedAt != null) {
                serializer.startTag(null, "submittedAt")
                serializer.text(metadata.submittedAt)
                serializer.endTag(null, "submittedAt")
            }
            
            if (metadata.deletedAt != null) {
                serializer.startTag(null, "deletedAt")
                serializer.text(metadata.deletedAt)
                serializer.endTag(null, "deletedAt")
            }
            if (metadata.uploadedAt != null) {
                serializer.startTag(null, "uploadedAt")
                serializer.text(metadata.uploadedAt)
                serializer.endTag(null, "uploadedAt")
            }
            serializer.endTag(null, "siteMetadata")
            serializer.endDocument()
            
            return writer.toString()
        }
        
        /**
         * Deserializes site metadata from XML
         */
        fun fromXml(xml: String): SiteMetadata? {
            return try {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(xml.reader())
                
                var eventType = parser.eventType
                var siteName = ""
                var createdAt = ""
                var teamConfigId = ""
                var teamConfigVersion = ""
                var submittedAt: String? = null
                var deletedAt: String? = null
                var uploadedAt: String? = null
                var currentTag: String? = null
                
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                        }
                        XmlPullParser.TEXT -> {
                            when (currentTag) {
                                "siteName" -> siteName = parser.text ?: ""
                                "createdAt" -> createdAt = parser.text ?: ""
                                "teamConfigId" -> teamConfigId = parser.text ?: ""
                                "teamConfigVersion" -> teamConfigVersion = parser.text ?: ""
                                "submittedAt" -> submittedAt = parser.text
                                "deletedAt" -> deletedAt = parser.text
                                "uploadedAt" -> uploadedAt = parser.text
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            currentTag = null
                        }
                    }
                    eventType = parser.next()
                }
                
                if (siteName.isNotEmpty() && createdAt.isNotEmpty()) {
                    SiteMetadata(
                        siteName = siteName,
                        createdAt = createdAt,
                        teamConfigId = teamConfigId,
                        teamConfigVersion = teamConfigVersion,
                        submittedAt = submittedAt,
                        deletedAt = deletedAt,
                        uploadedAt = uploadedAt
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("SiteMetadata", "Error parsing site metadata XML: ${e.message}", e)
                null
            }
        }
    }
}
