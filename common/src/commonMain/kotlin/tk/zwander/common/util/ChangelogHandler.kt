package tk.zwander.common.util

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import tk.zwander.common.data.changelog.Changelog
import tk.zwander.common.data.changelog.Changelogs

object ChangelogHandler {
    private const val DOMAIN_URL = "https://doc.samsungmobile.com:443"

    suspend fun getChangelog(device: String, region: String, firmware: String, useProxy: Boolean = tk.zwander.common.util.useProxy): Changelog? {
        return getChangelogs(device, region, useProxy)?.changelogs?.get(firmware)
    }

    suspend fun getChangelogs(device: String, region: String, useProxy: Boolean = tk.zwander.common.util.useProxy): Changelogs? {
        val outerUrl = generateUrlForDeviceAndRegion(device, region, useProxy)
        val outerResponse = try {
            client.use {
                it.get {
                    url(outerUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        val iframeUrl = if (outerResponse.status.isSuccess()) {
            parseDocUrl(outerResponse.bodyAsText())
                ?.replace("../../", generateProperUrl(useProxy, "$DOMAIN_URL/"))
        } else {
            println("No changelogs found for $device $region")
            return null
        }

        val iframeResponse = client.use {
            it.get {
                url(iframeUrl ?: return null)
            }
        }

        return if (iframeResponse.status.isSuccess()) {
            Changelogs(device, region, parseChangelogs(
                iframeResponse.bodyAsText()
            ))
        } else {
            println("Unable to load changelogs for $device $region")
            null
        }
    }

    private fun generateUrlForDeviceAndRegion(device: String, region: String, useProxy: Boolean): String {
        return generateProperUrl(useProxy, "$DOMAIN_URL/${device}/${region}/doc.html")
    }

    private fun parseDocUrl(body: String): String? {
        val doc = Ksoup.parse(body)
        val selector = doc.selectFirst("#sel_lang_hidden")
        val engOption = selector?.children()?.run { find { it.attr("value") == "EN" } ?: first() }

        return engOption?.text()
    }

    private fun parseChangelogs(body: String): Map<String, Changelog> {
        val doc = Ksoup.parse(body)
        val container = doc.selectFirst(".container")

        val divs = container!!.children().apply {
            removeIf { it.tagName() == "hr" }
        }
        val changelogs = LinkedHashMap<String, Changelog>()

        for (i in 3 until divs.size step 2) {
            val row = divs[i].children()
            val log = divs[i + 1]

            //This is kind of messy, but Samsung doesn't have a proper API for retrieving
            //version info. Some firmware entries don't have a security patch field, so
            //this handles that case. Some entries are in other languages, so using text
            //searching doesn't work well. It's possible some entries are missing other
            //fields, but there aren't any examples of that yet.
            val (build, androidVer, relDate, secPatch, _) = when {
                row.count() == 4 -> {
                    Changelog(
                        row[0].text().split(":")[1].trim(),
                        row[1].text().split(":")[1].trim(),
                        row[2].text().split(":")[1].trim(),
                        row[3].text().split(":")[1].trim(),
                        null
                    )
                }
                row.count() == 3 -> {
                    Changelog(
                        row[0].text().split(":")[1].trim(),
                        row[1].text().split(":")[1].trim(),
                        row[2].text().split(":")[1].trim(),
                        null, null
                    )
                }
                else -> {
                    Changelog(null, null, null, null, null)
                }
            }

            val logText = log.children()[0].childNodes().joinToString(
                separator = "",
                transform = {
                    it.outerHtml().lines().joinToString("\n") { line ->
                        if (line.startsWith(" ")) {
                            line.replaceFirst(" ", "")
                        } else {
                            line
                        }
                    }
                },
            )

            if (build != null) {
                changelogs[build] = Changelog(
                    build, androidVer, relDate, secPatch, logText
                )
            }
        }

        return changelogs
    }
}
