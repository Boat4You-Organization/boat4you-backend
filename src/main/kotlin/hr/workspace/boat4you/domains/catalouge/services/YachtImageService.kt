package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.ImageUtils
import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.opencv.imgproc.Imgproc
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.File

@Service
class YachtImageService(
    private val yachtImageRepository: YachtImageRepository,
) {
    @Value("\${application.upload.directory}")
    private lateinit var uploadDir: String

    fun resizeImage(
        imageId: Long,
        width: Int?,
        height: Int?,
    ): Resource {
        val yachtImage =
            yachtImageRepository
                .findById(imageId)
                .orElseThrow { ImageNotFoundException() }

        val file = File(uploadDir + "/" + yachtImage.url)
        if (!file.exists()) {
            throw ImageNotFoundException()
        }

        val result =
            if (width == null) {
                file.readBytes()
            } else {
                ImageUtils.resizeImage(file.absolutePath, width, height, 90, Imgproc.INTER_LINEAR)
            }
        return ByteArrayResource(result)
    }
}
