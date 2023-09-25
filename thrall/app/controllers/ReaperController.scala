package controllers

import akka.actor.Scheduler
import com.gu.mediaservice.lib.ImageIngestOperations
import com.gu.mediaservice.lib.auth.Permissions.DeleteImage
import com.gu.mediaservice.lib.auth.{Authentication, Authorisation, BaseControllerWithLoginRedirects}
import com.gu.mediaservice.lib.config.Services
import com.gu.mediaservice.lib.elasticsearch.ReapableEligibility
import com.gu.mediaservice.lib.logging.{GridLogging, MarkerMap}
import com.gu.mediaservice.lib.metadata.SoftDeletedMetadataTable
import com.gu.mediaservice.model.{ImageStatusRecord, SoftDeletedMetadata}
import lib.{ThrallConfig, ThrallStore}
import lib.elasticsearch.ElasticSearch
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.language.postfixOps

class ReaperController(
  es: ElasticSearch,
  store: ThrallStore,
  authorisation: Authorisation,
  config: ThrallConfig,
  scheduler: Scheduler,
  softDeletedMetadataTable: SoftDeletedMetadataTable,
  override val auth: Authentication,
  override val services: Services,
  override val controllerComponents: ControllerComponents,
)(implicit val ec: ExecutionContext) extends BaseControllerWithLoginRedirects with GridLogging {

  private val CONTROL_FILE_NAME = "PAUSED"

  private val INTERVAL = 15 minutes // based on max of 1000 per reap, this interval will max out at 96,000 images per day
  private val REAPS_PER_WEEK = 7.days / INTERVAL

  implicit val logMarker: MarkerMap = MarkerMap()

  private def getIsReapable = new ReapableEligibility {
    override val persistedRootCollections: List[String] = config.persistedRootCollections
    override val persistenceIdentifier: String = config.persistenceIdentifier
  }

  config.maybeReaperBucket.foreach { reaperBucket =>
    scheduler.scheduleAtFixedRate(
      initialDelay = 0 seconds,
      interval = INTERVAL,
    ){ () =>
      if(store.client.doesObjectExist(reaperBucket, CONTROL_FILE_NAME)) {
        logger.info("Reaper is paused")
      } else {
        es.countImagesIngestedInLast(7 days)(DateTime.now(DateTimeZone.UTC)).flatMap { imagesIngestedInLast7Days =>

          val imagesIngestedPer15Mins = imagesIngestedInLast7Days / REAPS_PER_WEEK

          val countOfImagesToReap = Math.min(imagesIngestedPer15Mins, 1000).toInt

          if (countOfImagesToReap == 1000) {
            logger.warn(s"Reaper is reaping at maximum rate of 1000 images per $INTERVAL. If this persists, the INTERVAL will need to become more frequent.")
          }

          val isReapable = getIsReapable

          Future.sequence(Seq(
            doBatchSoftReap(countOfImagesToReap, deletedBy = "reaper", isReapable),
            doBatchHardReap(countOfImagesToReap, deletedBy = "reaper", isReapable)
          ))
        }
      }
    }
  }

  private def batchDeleteWrapper(count: Int)(func: (Int, String, ReapableEligibility) => Future[JsValue]) = auth.async { request =>
    if (!authorisation.hasPermissionTo(DeleteImage)(request.user)) {
      Future.successful(Forbidden)
    }
    else if (count > 1000) {
      Future.successful(BadRequest("Too many IDs. Maximum 1000."))
    }
    else {
      func(
        count,
        request.user.accessor.identity,
        getIsReapable
      ).map(Ok(_))
    }
  }

  private def persistedBatchDeleteOperation(deleteType: String)(doBatchDelete: => Future[JsValue]) = config.maybeReaperBucket match {
    case None => Future.failed(new Exception("Reaper bucket not configured"))
    case Some(reaperBucket) => doBatchDelete.map { json =>
      val now = DateTime.now(DateTimeZone.UTC)
      val key = s"$deleteType/${now.toString("YYYY-MM-dd")}/$deleteType-${now.toString()}.json"
      store.client.putObject(reaperBucket, key, json.toString())
      json
    }
  }

  def doBatchSoftReap(count: Int): Action[AnyContent] = batchDeleteWrapper(count)(doBatchSoftReap)

  def doBatchSoftReap(count: Int, deletedBy: String, isReapable: ReapableEligibility): Future[JsValue] = persistedBatchDeleteOperation("soft"){

    logger.info(s"Soft deleting next $count images...")

    val deleteTime = DateTime.now(DateTimeZone.UTC)

    (for {
      idsSoftDeletedInES: Set[String] <- es.softDeleteNextBatchOfImages(isReapable, count, SoftDeletedMetadata(deleteTime, deletedBy))
      if idsSoftDeletedInES.nonEmpty
      dynamoStatuses <- softDeletedMetadataTable.setStatuses(idsSoftDeletedInES.map(
        ImageStatusRecord(
          _,
          deletedBy,
          deleteTime = deleteTime.toString,
          isDeleted = true
        )
      ))
    } yield idsSoftDeletedInES.map { id =>
      val detail = Map(
        "dynamo" -> !dynamoStatuses.exists(_.getUnprocessedItems.values().asScala.exists(_.asScala.exists(_.getPutRequest.getItem.get("id").getS == id))),
      )
      logger.info(s"Soft deleted image $id : $detail")
      id -> detail
    }.toMap).map(Json.toJson(_))
  }



  def doBatchHardReap(count: Int): Action[AnyContent] = batchDeleteWrapper(count)(doBatchHardReap)

  def doBatchHardReap(count: Int, deletedBy: String, isReapable: ReapableEligibility): Future[JsValue] = persistedBatchDeleteOperation("hard"){

    logger.info(s"Hard deleting next $count images...")

    (for {
      idsHardDeletedFromES: Set[String] <- es.hardDeleteNextBatchOfImages(isReapable, count)
      if idsHardDeletedFromES.nonEmpty
      mainImagesS3Deletions <- store.deleteOriginals(idsHardDeletedFromES)
      thumbsS3Deletions <- store.deleteThumbnails(idsHardDeletedFromES)
      pngsS3Deletions <- store.deletePNGs(idsHardDeletedFromES)
    } yield idsHardDeletedFromES.map { id =>
      val detail = Map(
        "ES" -> Some(true), // since this is list of IDs deleted from ES
        "mainImage" -> mainImagesS3Deletions.get(ImageIngestOperations.fileKeyFromId(id)),
        "thumb" -> thumbsS3Deletions.get(ImageIngestOperations.fileKeyFromId(id)),
        "optimisedPng" -> pngsS3Deletions.get(ImageIngestOperations.optimisedPngKeyFromId(id))
      )
      logger.info(s"Hard deleted image $id : $detail")
      id -> detail
    }.toMap).map(Json.toJson(_))
  }

}
