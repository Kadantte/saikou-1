package ani.saikou.manga.mangareader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.saikou.*
import ani.saikou.anilist.Anilist
import ani.saikou.databinding.ActivityMangaReaderBinding
import ani.saikou.manga.DualPageAdapter
import ani.saikou.manga.MangaChapter
import ani.saikou.media.Media
import ani.saikou.media.MediaDetailsViewModel
import ani.saikou.parsers.HMangaSources
import ani.saikou.parsers.MangaSources
import ani.saikou.settings.CurrentReaderSettings.Directions.*
import ani.saikou.settings.CurrentReaderSettings.DualPageModes.*
import ani.saikou.settings.CurrentReaderSettings.Layouts.CONTINUOUS_PAGED
import ani.saikou.settings.CurrentReaderSettings.Layouts.PAGED
import ani.saikou.settings.ReaderSettings
import ani.saikou.settings.UserInterfaceSettings
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.min

@SuppressLint("SetTextI18n")
class MangaReaderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMangaReaderBinding
    private val model: MediaDetailsViewModel by viewModels()
    private val scope = lifecycleScope

    private lateinit var media: Media
    private lateinit var chapter: MangaChapter
    private lateinit var chapters: MutableMap<String, MangaChapter>
    private lateinit var chaptersArr: List<String>
    private lateinit var chaptersTitleArr: ArrayList<String>
    private var currentChapterIndex = 0

    private var isContVisible = false
    private var showProgressDialog = true
    private var progressDialog: AlertDialog.Builder? = null
    private var maxChapterPage = 0L
    private var currentChapterPage = 0L

    var settings = loadData("reader_settings") ?: ReaderSettings().apply { saveData("reader_settings", this) }
    var uiSettings = loadData("ui_settings") ?: UserInterfaceSettings().apply { saveData("ui_settings", this) }

    private var notchHeight: Int? = null

    private var imageAdapter: BaseImageAdapter? = null

    var sliding = false
    var isAnimating = false

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !settings.showSystemBars) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight = min(displayCutout.boundingRects[0].width(), displayCutout.boundingRects[0].height())
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }

    private fun checkNotch() {
        binding.mangaReaderTopLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = notchHeight ?: return
        }
    }

    private fun hideBars() {
        if (!settings.showSystemBars) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideBars()

        binding.mangaReaderBack.setOnClickListener {
            onBackPressed()
        }

        var pageSliderTimer = Timer()
        fun pageSliderHide() {
            pageSliderTimer.cancel()
            pageSliderTimer.purge()
            val timerTask: TimerTask = object : TimerTask() {
                override fun run() {
                    binding.mangaReaderCont.post {
                        sliding = false
                        handleController(false)
                    }
                }
            }
            pageSliderTimer = Timer()
            pageSliderTimer.schedule(timerTask, 3000)
        }

        binding.mangaReaderPageSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sliding = true
                if (settings.default.layout != PAGED)
                    binding.mangaReaderRecycler.smoothScrollToPosition((value.toInt() - 1) / (dualPage { 2 } ?: 1))
                else
                    binding.mangaReaderPager.currentItem = (value.toInt() - 1) / (dualPage { 2 } ?: 1)
                pageSliderHide()
            }
        }

        media = if (model.getMedia().value == null)
            try {
                (intent.getSerializableExtra("media") as? Media) ?: return
            } catch (e: Exception) {
                logError(e)
                return
            }
        else model.getMedia().value ?: return
        model.setMedia(media)

        settings = loadData("${media.id}_reader_settings") ?: settings

        chapters = media.manga?.chapters ?: return
        chapter = chapters[media.manga!!.selectedChapter] ?: return

        model.mangaReadSources = if (media.isAdult) HMangaSources else MangaSources
        binding.mangaReaderSource.text = model.mangaReadSources!!.names[media.selected!!.source]

        binding.mangaReaderTitle.text = media.userPreferredName

        chaptersArr = chapters.keys.toList()
        currentChapterIndex = chaptersArr.indexOf(media.manga!!.selectedChapter)

        chaptersTitleArr = arrayListOf()
        chapters.forEach {
            val chapter = it.value
            chaptersTitleArr.add("${if (!chapter.title.isNullOrEmpty() && chapter.title != "null") "" else "Chapter "}${chapter.number}${if (!chapter.title.isNullOrEmpty() && chapter.title != "null") " : " + chapter.title else ""}")
        }

        showProgressDialog = if (settings.askIndividual) loadData<Boolean>("${media.id}_progressDialog") != true else false
        progressDialog =
            if (showProgressDialog && Anilist.userid != null && if (media.isAdult) settings.updateForH else true)
                AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Update progress on anilist?").apply {
                    setMultiChoiceItems(
                        arrayOf("Don't ask again for ${media.userPreferredName}"),
                        booleanArrayOf(false)
                    ) { _, _, isChecked ->
                        if (isChecked) {
                            saveData("${media.id}_progressDialog", isChecked)
                            progressDialog = null
                        }
                        showProgressDialog = isChecked
                    }
                    setOnCancelListener { hideBars() }
                }
            else null

        //Chapter Change
        fun change(index: Int) {
            saveData("${media.id}_${chaptersArr[currentChapterIndex]}", currentChapterPage, this)
            maxChapterPage = 0
            media.manga!!.selectedChapter = chaptersArr[index]
            model.setMedia(media)
            scope.launch(Dispatchers.IO) { model.loadMangaChapterImages(chapters[chaptersArr[index]]!!, media.selected!!) }
        }

        //ChapterSelector
        binding.mangaReaderChapterSelect.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, chaptersTitleArr)
        binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
        binding.mangaReaderChapterSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position != currentChapterIndex) change(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.mangaReaderSettings.setSafeOnClickListener {
            ReaderSettingsDialogFragment(this).show(supportFragmentManager, "settings")
        }

        //Next Chapter
        binding.mangaReaderNextChap.setOnClickListener {
            binding.mangaReaderNextChapter.performClick()
        }
        binding.mangaReaderNextChapter.setOnClickListener {
            if (chaptersArr.size > currentChapterIndex + 1) progress { change(currentChapterIndex + 1) }
            else toastString("Next Chapter Not Found")
        }
        //Prev Chapter
        binding.mangaReaderPrevChap.setOnClickListener {
            binding.mangaReaderPreviousChapter.performClick()
        }
        binding.mangaReaderPreviousChapter.setOnClickListener {
            if (currentChapterIndex > 0) change(currentChapterIndex - 1)
            else toastString("This is the 1st Chapter!")
        }

        val chapterObserverRunnable = Runnable {
            model.getMangaChapter().observe(this) {
                if (it != null) {
                    chapter = it
                    media.selected = model.loadSelected(media)
                    saveData("${media.id}_current_chp", it.number, this)
                    currentChapterIndex = chaptersArr.indexOf(it.number)
                    binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                    binding.mangaReaderNextChap.text = chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
                    binding.mangaReaderPrevChap.text = chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""

                    applySettings()
                }
            }
        }
        chapterObserverRunnable.run()

        scope.launch(Dispatchers.IO) { model.loadMangaChapterImages(chapter, media.selected!!) }
    }

    private val snapHelper = LinearSnapHelper()

    private fun <T> dualPage(callback: () -> T): T? {
        return when (settings.default.dualPageMode) {
            No        -> null
            Automatic -> {
                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) callback.invoke()
                else null
            }
            Force     -> callback.invoke()
        }
    }

    fun applySettings() {
        saveData("${media.id}_reader_settings", settings)
        hideBars()

        SubsamplingScaleImageView.setPreferredBitmapConfig(
            if (settings.default.trueColors) Bitmap.Config.ARGB_8888
            else Bitmap.Config.RGB_565
        )

        binding.mangaReaderPager.unregisterOnPageChangeCallback(pageChangeCallback)

        currentChapterPage = loadData("${media.id}_${chapter.number}", this) ?: 1
        val chapImages = chapter.images
        if (!chapImages.isNullOrEmpty()) {
            maxChapterPage = chapImages.size.toLong()
            saveData("${media.id}_${chapter.number}_max", maxChapterPage)

            imageAdapter = dualPage { DualPageAdapter(this, chapter) } ?: ImageAdapter(this, chapter)

            if (chapImages.size > 1) {
                binding.mangaReaderPageSlider.apply {
                    visibility = View.VISIBLE
                    value = currentChapterPage.toFloat()
                    valueTo = maxChapterPage.toFloat()
                }
            } else {
                binding.mangaReaderPageSlider.visibility = View.GONE
            }
            binding.mangaReaderPageNumber.text = "${currentChapterPage}/$maxChapterPage"

        }

        val currentPage = currentChapterPage.toInt()
        if (settings.default.layout != PAGED) {

            binding.mangaReaderRecycler.visibility = View.VISIBLE
            binding.mangaReaderPager.visibility = View.GONE
            binding.mangaReaderRecycler.clearOnScrollListeners()

            val layoutManager = LinearLayoutManager(
                this,
                if (settings.default.direction == TOP_TO_BOTTOM || settings.default.direction == BOTTOM_TO_TOP) RecyclerView.VERTICAL
                else RecyclerView.HORIZONTAL,
                !(settings.default.direction == TOP_TO_BOTTOM || settings.default.direction == LEFT_TO_RIGHT)
            )
            layoutManager.isItemPrefetchEnabled = true
            layoutManager.initialPrefetchItemCount = 3

            binding.mangaReaderRecycler.layoutManager = layoutManager

            binding.mangaReaderRecycler.tapListener = {
                handleController()
            }
            binding.mangaReaderRecycler.longTapListener = { event ->
                binding.mangaReaderRecycler.findChildViewUnder(event.x, event.y).let { child ->
                    child ?: return@let false
                    imageAdapter?.loadImage(binding.mangaReaderRecycler.getChildAdapterPosition(child), child) ?: false
                }
            }

            binding.mangaReaderRecycler.adapter = imageAdapter

            binding.mangaReaderRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                    if (
                        (
                                (settings.default.direction == TOP_TO_BOTTOM || settings.default.direction == BOTTOM_TO_TOP)
                                        &&
                                        (!v.canScrollVertically(-1) || !v.canScrollVertically(1))
                                ) || (
                                (settings.default.direction == LEFT_TO_RIGHT || settings.default.direction == RIGHT_TO_LEFT)
                                        &&
                                        (!v.canScrollHorizontally(-1) || !v.canScrollHorizontally(1))
                                )
                    ) {
                        handleController(true)
                    } else handleController(false)

                    updatePageNumber(layoutManager.findLastVisibleItemPosition().toLong() * (dualPage { 2 } ?: 1) + 1)
                    super.onScrolled(v, dx, dy)
                }
            })

            if ((settings.default.direction == TOP_TO_BOTTOM || settings.default.direction == BOTTOM_TO_TOP))
                binding.mangaReaderRecycler.updatePadding(0, 128f.px, 0, 128f.px)
            else
                binding.mangaReaderRecycler.updatePadding(128f.px, 0, 128f.px, 0)

            snapHelper.attachToRecyclerView(
                if (settings.default.layout == CONTINUOUS_PAGED) binding.mangaReaderRecycler
                else null
            )
            binding.mangaReaderRecycler.scrollToPosition(currentPage - 1)
        } else {
            binding.mangaReaderRecycler.visibility = View.GONE
            binding.mangaReaderPager.apply {
                visibility = View.VISIBLE
                adapter = imageAdapter
                layoutDirection =
                    if (settings.default.direction == BOTTOM_TO_TOP || settings.default.direction == RIGHT_TO_LEFT)
                        View.LAYOUT_DIRECTION_LTR
                    else View.LAYOUT_DIRECTION_RTL
                orientation =
                    if (settings.default.direction == LEFT_TO_RIGHT || settings.default.direction == RIGHT_TO_LEFT)
                        ViewPager2.ORIENTATION_HORIZONTAL
                    else ViewPager2.ORIENTATION_VERTICAL
                registerOnPageChangeCallback(pageChangeCallback)
                setOnClickListener {
                    handleController()
                }
                setCurrentItem(currentPage - 1, false)
            }

        }

    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updatePageNumber(position.toLong() * (dualPage { 2 } ?: 1) + 1)
            handleController(position == 0 || position + 1 >= maxChapterPage)
            super.onPageSelected(position)
        }
    }

    private val overshoot = OvershootInterpolator(1.4f)
    private val controllerDuration = (uiSettings.animationSpeed * 200).toLong()
    private var goneTimer = Timer()
    fun gone() {
        goneTimer.cancel()
        goneTimer.purge()
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                if (!isContVisible) binding.mangaReaderCont.post {
                    binding.mangaReaderCont.visibility = View.GONE
                    isAnimating = false
                }
            }
        }
        goneTimer = Timer()
        goneTimer.schedule(timerTask, controllerDuration)
    }

    fun handleController(shouldShow: Boolean? = null) {
        if (!sliding) {
            if (!settings.showSystemBars) {
                hideBars()
                checkNotch()
            }
            shouldShow?.apply { isContVisible = !this }
            if (isContVisible) {
                isContVisible = false
                if (!isAnimating) {
                    isAnimating = true
                    ObjectAnimator.ofFloat(binding.mangaReaderCont, "alpha", 1f, 0f).setDuration(controllerDuration).start()
                    ObjectAnimator.ofFloat(binding.mangaReaderBottomLayout, "translationY", 0f, 128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(binding.mangaReaderTopLayout, "translationY", 0f, -128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                }
                gone()
            } else {
                isContVisible = true
                binding.mangaReaderCont.visibility = View.VISIBLE
                ObjectAnimator.ofFloat(binding.mangaReaderCont, "alpha", 0f, 1f).setDuration(controllerDuration).start()
                ObjectAnimator.ofFloat(binding.mangaReaderTopLayout, "translationY", -128f, 0f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
                ObjectAnimator.ofFloat(binding.mangaReaderBottomLayout, "translationY", 128f, 0f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
            }
        }
    }

    fun updatePageNumber(page: Long) {
        if (currentChapterPage != page) {
            currentChapterPage = page
            saveData("${media.id}_${chapter.number}", page, this)
            binding.mangaReaderPageNumber.text = "${currentChapterPage}/$maxChapterPage"
            if (!sliding) binding.mangaReaderPageSlider.value = currentChapterPage.toFloat()
        }
        if (maxChapterPage - currentChapterPage <= 1) scope.launch(Dispatchers.IO) {
            model.loadMangaChapterImages(
                chapters[chaptersArr.getOrNull(currentChapterIndex + 1) ?: return@launch]!!,
                media.selected!!,
                false
            )
        }
    }

    private fun progress(runnable: Runnable) {
        if (maxChapterPage - currentChapterPage <= 1 && Anilist.userid != null) {
            if (showProgressDialog && progressDialog != null) {
                progressDialog?.setCancelable(false)
                    ?.setPositiveButton("Yes") { dialog, _ ->
                        saveData("${media.id}_save_progress", true)
                        updateAnilistProgress(media, media.manga!!.selectedChapter!!)
                        dialog.dismiss()
                        runnable.run()
                    }
                    ?.setNegativeButton("No") { dialog, _ ->
                        saveData("${media.id}_save_progress", false)
                        dialog.dismiss()
                        runnable.run()
                    }
                progressDialog?.show()
            } else {
                if (loadData<Boolean>("${media.id}_save_progress") != false && if (media.isAdult) settings.updateForH else true)
                    updateAnilistProgress(media, media.manga!!.selectedChapter!!)
                runnable.run()
            }
        } else {
            runnable.run()
        }
    }

    override fun onBackPressed() {
        progress { super.onBackPressed() }
    }
}