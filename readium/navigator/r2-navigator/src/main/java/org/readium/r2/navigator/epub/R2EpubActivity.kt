/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.navigator.epub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.ActionMode
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.*
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.shared.FragmentNavigator
import org.readium.r2.shared.extensions.getPublication
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import kotlin.coroutines.CoroutineContext

@OptIn(FragmentNavigator::class)
open class R2EpubActivity: AppCompatActivity(), IR2Activity, IR2Selectable, IR2Highlightable, IR2TTS, CoroutineScope, VisualNavigator, Navigator.VisualListener {

    /**
     * Context of this scope.
     */
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    override lateinit var preferences: SharedPreferences
    override lateinit var resourcePager: R2ViewPager
    override lateinit var publicationPath: String
    override lateinit var publicationFileName: String
    override lateinit var publication: Publication
    override lateinit var publicationIdentifier: String
    override var bookId: Long = -1

    override var allowToggleActionBar = true

    protected var navigatorDelegate: NavigatorDelegate? = null

    private val r2PagerAdapter: R2PagerAdapter
        get() = resourcePager.adapter as R2PagerAdapter

    private val currentFragment: R2EpubPageFragment? get() =
        r2PagerAdapter.mFragments.get(r2PagerAdapter.getItemId(resourcePager.currentItem)) as? R2EpubPageFragment

    private val navigatorFragment: EpubNavigatorFragment get() =
        supportFragmentManager.findFragmentById(R.id.epub_navigator) as EpubNavigatorFragment

    private val positions: List<Locator> get() = navigatorFragment.positions

    override val currentLocator: LiveData<Locator?>
        get() = navigatorFragment.currentLocator

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)

        publication = intent.getPublication(this)
        publicationFileName = intent.getStringExtra("publicationFileName") ?: throw Exception("publicationFileName required")
        publicationIdentifier = publication.metadata.identifier!!

        val port = preferences.getString("$publicationIdentifier-publicationPort", 0.toString())!!.toInt()
        val baseUrl = Publication.localBaseUrlOf(publicationFileName, port)

        val initialLocator = intent.getParcelableExtra("locator") as? Locator

        supportFragmentManager.fragmentFactory = NavigatorFragmentFactory(publication, baseUrl = baseUrl, initialLocator = initialLocator, listener = this)

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_r2_epub)

        resourcePager = navigatorFragment.resourcePager

        title = null
    }

    override fun finish() {
        setResult(Activity.RESULT_OK, intent)
        super.finish()
    }

    override fun onActionModeStarted(mode: ActionMode?) {
        mode?.menu?.clear()
        super.onActionModeStarted(mode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2 && resultCode == Activity.RESULT_OK) {
            val locator = data?.getParcelableExtra("locator") as? Locator
            if (locator != null) {
                go(locator)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun toggleActionBar() {
        if (allowToggleActionBar) {
            launch {
                if (supportActionBar!!.isShowing) {
                    resourcePager.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                            or View.SYSTEM_UI_FLAG_IMMERSIVE)
                } else {
                    resourcePager.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                }
            }
        }
    }

    override val readingProgression: ReadingProgression
        get() = navigatorFragment.readingProgression

    override fun goLeft(animated: Boolean, completion: () -> Unit): Boolean {
        return navigatorFragment.goLeft(animated, completion)
    }

    override fun goRight(animated: Boolean, completion: () -> Unit): Boolean {
        return navigatorFragment.goRight(animated, completion)
    }

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        navigatorFragment.go(locator, animated, completion)

        if (allowToggleActionBar && supportActionBar!!.isShowing) {
            resourcePager.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                    or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }

        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        return navigatorFragment.go(link, animated, completion)
    }

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        return navigatorFragment.goForward(animated, completion)
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        return navigatorFragment.goBackward(animated, completion)
    }

    override fun onTap(point: PointF): Boolean {
        toggleActionBar()
        return super.onTap(point)
    }

    override fun currentSelection(callback: (Locator?) -> Unit) {
        currentFragment?.webView?.getCurrentSelectionInfo {
            val selection = JSONObject(it)
            val resource = publication.readingOrder[resourcePager.currentItem]
            val resourceHref = resource.href
            val resourceType = resource.type ?: ""
            val locations = Locator.Locations.fromJSON(selection.getJSONObject("locations"))
            val text = Locator.Text.fromJSON(selection.getJSONObject("text"))

            val locator = Locator(
                href = resourceHref,
                type = resourceType,
                locations = locations,
                text = text
            )
            callback(locator)
        }
    }

    override fun showHighlight(highlight: Highlight) {
        currentFragment?.webView?.run {
            val colorJson = JSONObject().apply {
                put("red", Color.red(highlight.color))
                put("green", Color.green(highlight.color))
                put("blue", Color.blue(highlight.color))
            }
            createHighlight(highlight.locator.toJSON().toString(), colorJson.toString()) {
                if (highlight.annotationMarkStyle.isNullOrEmpty().not())
                    createAnnotation(highlight.id)
            }
        }
    }

    override fun showHighlights(highlights: Array<Highlight>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun hideHighlightWithID(id: String) {
        currentFragment?.webView?.destroyHighlight(id)
        currentFragment?.webView?.destroyHighlight(id.replace("HIGHLIGHT", "ANNOTATION"))
    }

    override fun hideAllHighlights() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun rectangleForHighlightWithID(id: String, callback: (Rect?) -> Unit) {
        currentFragment?.webView?.rectangleForHighlightWithID(id) {
            val rect = JSONObject(it).run {
                try {
                    val display = windowManager.defaultDisplay
                    val metrics = DisplayMetrics()
                    display.getMetrics(metrics)
                    val left = getDouble("left")
                    val width = getDouble("width")
                    val top = getDouble("top") * metrics.density
                    val height = getDouble("height") * metrics.density
                    Rect(left.toInt(), top.toInt(), width.toInt() + left.toInt(), top.toInt() + height.toInt())
                } catch (e: JSONException) {
                    null
                }
            }
            callback(rect)
        }
    }

    override fun rectangleForHighlightAnnotationMarkWithID(id: String): Rect? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun registerHighlightAnnotationMarkStyle(name: String, css: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun highlightActivated(id: String) {
    }

    override fun highlightAnnotationMarkActivated(id: String) {
    }

    fun createHighlight(color: Int, callback: (Highlight) -> Unit) {
        currentSelection { locator ->
            val colorJson = JSONObject().apply {
                put("red", Color.red(color))
                put("green", Color.green(color))
                put("blue", Color.blue(color))
            }

            currentFragment?.webView?.createHighlight(locator?.toJSON().toString(), colorJson.toString()) {
                val json = JSONObject(it)
                val id = json.getString("id")
                callback(
                        Highlight(
                                id,
                                locator!!,
                                color,
                                Style.highlight
                        )
                )
            }
        }
    }

    fun createAnnotation(highlight: Highlight?, callback: (Highlight) -> Unit) {
        if (highlight != null) {
            currentFragment?.webView?.createAnnotation(highlight.id)
            callback(highlight)
        } else {
            createHighlight(Color.rgb(150, 150, 150)) {
                createAnnotation(it) { highlight ->
                    callback(highlight)
                }
            }
        }
    }
}
