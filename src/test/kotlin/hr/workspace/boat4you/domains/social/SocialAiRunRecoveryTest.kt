package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.social.ai.AiPostDraft
import hr.workspace.boat4you.domains.social.ai.HiggsfieldClient
import hr.workspace.boat4you.domains.social.ai.PickedBoat
import hr.workspace.boat4you.domains.social.ai.SocialAiService
import hr.workspace.boat4you.domains.social.ai.SocialAiWriter
import hr.workspace.boat4you.domains.social.ai.SocialBoatPicker
import hr.workspace.boat4you.domains.social.dto.SocialMediaDto
import hr.workspace.boat4you.domains.social.dto.SocialPostDto
import hr.workspace.boat4you.domains.social.dto.SocialPostRequest
import hr.workspace.boat4you.domains.social.dto.SocialPostTargetDto
import hr.workspace.boat4you.domains.social.jpa.SocialAccount
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAiRun
import hr.workspace.boat4you.domains.social.jpa.SocialAiRunRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAiRunStatus
import hr.workspace.boat4you.domains.social.jpa.SocialAiSetting
import hr.workspace.boat4you.domains.social.jpa.SocialAiSettingRepository
import hr.workspace.boat4you.domains.social.jpa.SocialGeneration
import hr.workspace.boat4you.domains.social.jpa.SocialGenerationRepository
import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPostAction
import hr.workspace.boat4you.domains.social.jpa.SocialPostSource
import hr.workspace.boat4you.domains.social.jpa.SocialPostStatus
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import hr.workspace.boat4you.domains.social.service.SocialConflictException
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import hr.workspace.boat4you.domains.social.service.SocialPostService
import hr.workspace.boat4you.domains.social.service.SocialValidationException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

/**
 * The daily AI post when scheduling its draft is refused (10.11.2026: a catalogue photo outside Instagram's ratio).
 * The draft is recorded on the run before scheduling, so a re-run takes it up instead of making a second one, and the
 * video is still queued.
 */
class SocialAiRunRecoveryTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    /** Runs the callback with a plain status; the repositories here are mocks, there is nothing to commit. */
    private fun noTransactions(): PlatformTransactionManager =
        mock(PlatformTransactionManager::class.java).also {
            `when`(it.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        }

    private val site = "boat4you"
    private val date: LocalDate = LocalDate.of(2026, 11, 10)

    private val settings = mock(SocialAiSettingRepository::class.java)
    private val runs = mock(SocialAiRunRepository::class.java)
    private val generations = mock(SocialGenerationRepository::class.java)
    private val accounts = mock(SocialAccountRepository::class.java)
    private val picker = mock(SocialBoatPicker::class.java)
    private val writer = mock(SocialAiWriter::class.java)
    private val store = mock(SocialMediaStore::class.java)
    private val postService = mock(SocialPostService::class.java)

    private val service =
        SocialAiService(
            settings,
            runs,
            generations,
            accounts,
            mock(SocialMediaRepository::class.java),
            BrandRegistry(),
            picker,
            writer,
            mock(HiggsfieldClient::class.java),
            store,
            postService,
            TestProps.props(anthropicApiKey = "test-key", higgsfieldKey = "test-hf"),
            TransactionTemplate(noTransactions()),
        )

    private val instagram = account(21, SocialPlatform.INSTAGRAM)
    private val facebook = account(22, SocialPlatform.FACEBOOK)
    private val youtube = account(23, SocialPlatform.YOUTUBE)

    private fun account(
        id: Long,
        platform: SocialPlatform,
    ) = SocialAccount().apply {
        this.id = id
        this.platform = platform
        this.site = this@SocialAiRunRecoveryTest.site
        name = platform.name.lowercase()
    }

    private fun setting(
        requireApproval: Boolean,
        useVideo: Boolean,
    ) = SocialAiSetting().apply {
        site = this@SocialAiRunRecoveryTest.site
        enabled = true
        publishTime = "18:00"
        accountIds = mutableListOf(21, 22, 23)
        this.requireApproval = requireApproval
        this.useVideo = useVideo
    }

    private fun photo(
        width: Int,
        height: Int,
    ) = SocialMediaDto(900, SocialMediaKind.IMAGE, "https://api.boat4you.com/m/900.jpg", null, "900.jpg", "image/jpeg", 1, width, height, null)

    private fun draftPost(
        id: Long,
        media: List<SocialMediaDto> = listOf(photo(1080, 1350)),
        channels: List<SocialAccount> = listOf(instagram, facebook),
    ) = SocialPostDto(
        id = id,
        text = "main",
        link = "https://www.boat4you.com/yacht/x",
        media = media,
        status = SocialPostStatus.DRAFT,
        site = site,
        scheduledAt = null,
        publishedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        createdBy = "AI",
        source = SocialPostSource.AI,
        note = null,
        targets =
            channels.map {
                SocialPostTargetDto(it.id!!, it.id!!, it.platform!!, it.name, site, null, null, SocialTargetStatus.PENDING, null, null, null, 0)
            },
    )

    /** The run row as the repository keeps it: one row, every save writes it back. */
    private fun storedRun(run: SocialAiRun) {
        `when`(runs.findBySiteAndRunDate(site, date)).thenReturn(run)
        `when`(runs.findById(run.id!!)).thenReturn(Optional.of(run))
        `when`(runs.save(any<SocialAiRun>())).thenAnswer { it.arguments[0] }
        `when`(runs.saveAndFlush(any<SocialAiRun>())).thenAnswer { (it.arguments[0] as SocialAiRun).apply { id = run.id } }
    }

    private fun aBoatAndTexts() {
        `when`(accounts.findAllById(any())).thenReturn(listOf(instagram, facebook, youtube))
        `when`(picker.pick(any(), any(), any(), any())).thenReturn(
            PickedBoat(77, "lagoon-42", "https://www.boat4you.com/yacht/lagoon-42", listOf(501, 502), mapOf("name" to "Lagoon 42")),
        )
        `when`(store.storeFromUrl(any(), any(), any(), any())).thenReturn(SocialMedia().apply { id = 900 })
        `when`(writer.write(any())).thenReturn(AiPostDraft("Lagoon 42 in Split", "main", "short", "ig", "tt", "yt title", "yt desc", "prompt"))
        `when`(generations.save(any<SocialGeneration>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `a refused schedule keeps the draft on the run, says why, and still queues the video`() {
        val run =
            SocialAiRun().apply {
                id = 5
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
            }
        storedRun(run)
        `when`(runs.findBySiteAndRunDate(site, date)).thenReturn(null) // no run yet today
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = false, useVideo = true)))
        aBoatAndTexts()
        `when`(postService.create(any(), any(), any())).thenReturn(draftPost(7))
        `when`(postService.get(7)).thenReturn(draftPost(7))
        `when`(postService.update(anyLong(), any(), any()))
            .thenThrow(SocialConflictException("The video for this post is still being made"))

        val dto = service.run(site, date, force = false)

        dto.postId shouldBe 7
        dto.status shouldBe SocialAiRunStatus.WAITING_VIDEO
        dto.error!! shouldContain "Post #7 stays a draft"
        dto.error!! shouldContain "The video for this post is still being made"
        run.topic!! shouldContain "yacht:77"
        verify(postService, times(1)).create(any(), any(), any())
        verify(generations, times(1)).save(any<SocialGeneration>())
    }

    @Test
    fun `a re-run of a failed run takes up its draft instead of making a second one`() {
        val run =
            SocialAiRun().apply {
                id = 6
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
                status = SocialAiRunStatus.FAILED
                error = "Connection reset"
                postId = 8
                topic = "yacht:77 Lagoon 42: Lagoon 42 in Split"
            }
        storedRun(run)
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = false, useVideo = false)))
        `when`(postService.get(8)).thenReturn(draftPost(8))
        `when`(postService.update(anyLong(), any(), any())).thenReturn(draftPost(8).copy(status = SocialPostStatus.SCHEDULED))

        val dto = service.run(site, date, force = true)

        dto.status shouldBe SocialAiRunStatus.DONE
        dto.postId shouldBe 8
        dto.error shouldBe null
        run.topic shouldBe "yacht:77 Lagoon 42: Lagoon 42 in Split"
        verify(postService, never()).create(any(), any(), any())
        verify(postService, times(1)).update(anyLong(), any(), any())
        verify(picker, never()).pick(any(), any(), any(), any())
        verify(writer, never()).write(any())
    }

    @Test
    fun `a re-run whose draft is still refused records the reason again and makes nothing new`() {
        val run =
            SocialAiRun().apply {
                id = 9
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
                status = SocialAiRunStatus.FAILED
                postId = 10
            }
        storedRun(run)
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = false, useVideo = false)))
        `when`(postService.get(10)).thenReturn(draftPost(10))
        `when`(postService.update(anyLong(), any(), any())).thenThrow(SocialValidationException("INSTAGRAM ig: needs a photo or video"))

        val dto = service.run(site, date, force = true)

        dto.status shouldBe SocialAiRunStatus.DONE
        dto.error!! shouldContain "Post #10 stays a draft"
        verify(postService, never()).create(any(), any(), any())
    }

    @Test
    fun `with approval required the re-run only marks the run done, the draft waits for us`() {
        val run =
            SocialAiRun().apply {
                id = 11
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
                status = SocialAiRunStatus.FAILED
                postId = 12
            }
        storedRun(run)
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = true, useVideo = false)))
        `when`(postService.get(12)).thenReturn(draftPost(12))

        val dto = service.run(site, date, force = true)

        dto.status shouldBe SocialAiRunStatus.DONE
        dto.error shouldBe null
        verify(postService, never()).update(anyLong(), any(), any())
        verify(postService, never()).create(any(), any(), any())
    }

    @Test
    fun `a channel its network would refuse is left out, the others are scheduled and the run says so`() {
        val run =
            SocialAiRun().apply {
                id = 13
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
                status = SocialAiRunStatus.FAILED
                postId = 14
            }
        storedRun(run)
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = false, useVideo = false)))
        val wide = draftPost(14, media = listOf(photo(2000, 800)))
        `when`(postService.get(14)).thenReturn(wide)
        `when`(postService.update(anyLong(), any(), any())).thenReturn(wide.copy(status = SocialPostStatus.SCHEDULED))

        val dto = service.run(site, date, force = true)

        val sent = ArgumentCaptor.forClass(SocialPostRequest::class.java)
        verify(postService).update(anyLong(), sent.capture() ?: SocialPostRequest(), any())
        sent.value.targets.map { it.accountId } shouldBe listOf(22L)
        sent.value.action shouldBe SocialPostAction.SCHEDULE
        dto.status shouldBe SocialAiRunStatus.DONE
        dto.error!! shouldContain "Post #14 scheduled without INSTAGRAM instagram: Instagram photos must be between 4:5 and 1.91:1"
    }

    @Test
    fun `with every channel refused the draft stays and nothing is scheduled`() {
        val run =
            SocialAiRun().apply {
                id = 15
                this.site = this@SocialAiRunRecoveryTest.site
                runDate = date
                status = SocialAiRunStatus.FAILED
                postId = 16
            }
        storedRun(run)
        `when`(settings.findById(site)).thenReturn(Optional.of(setting(requireApproval = false, useVideo = false)))
        `when`(postService.get(16)).thenReturn(draftPost(16, media = listOf(photo(2000, 800)), channels = listOf(instagram)))

        val dto = service.run(site, date, force = true)

        verify(postService, never()).update(anyLong(), any(), any())
        dto.error!! shouldContain "Post #16 stays a draft, not scheduled: INSTAGRAM instagram: Instagram photos must be between 4:5 and 1.91:1"
    }
}
