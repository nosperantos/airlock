package com.ing.wbaa.airlock.proxy.handler

import java.io.File

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.{Authority, Host}
import akka.http.scaladsl.model.{HttpRequest, RemoteAddress}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{CopyObjectRequest, ObjectMetadata}
import com.ing.wbaa.airlock.proxy.AirlockS3Proxy
import com.ing.wbaa.airlock.proxy.config.{HttpSettings, KafkaSettings, StorageS3Settings}
import com.ing.wbaa.airlock.proxy.data._
import com.ing.wbaa.airlock.proxy.provider.{MessageProviderKafka, SignatureProviderAws}
import com.ing.wbaa.testkit.AirlockFixtures
import org.scalatest._

import scala.collection.JavaConverters._
import scala.concurrent.Future

class RequestHandlerS3ItTest extends AsyncWordSpec with DiagrammedAssertions with AirlockFixtures {
  final implicit val testSystem: ActorSystem = ActorSystem.create("test-system")

  // Settings for tests:
  //  - Force a random port to listen on.
  //  - Explicitly bind to loopback, irrespective of any default value.
  val airlockHttpSettings: HttpSettings = new HttpSettings(testSystem.settings.config) {
    override val httpPort: Int = 0
    override val httpBind: String = "127.0.0.1"
  }

  /**
    * Fixture for starting and stopping a test proxy that tests can interact with.
    *
    * @param awsSignerType Signer type for aws sdk to use
    * @param testCode      Code that accepts the created sdk
    * @return Assertion
    */
  def withS3SdkToMockProxy(awsSignerType: String)(testCode: AmazonS3 => Assertion): Future[Assertion] = {
    val proxy: AirlockS3Proxy = new AirlockS3Proxy with RequestHandlerS3 with SignatureProviderAws
      with FilterRecursiveListBucketHandler with MessageProviderKafka {
      override implicit lazy val system: ActorSystem = testSystem
      override val httpSettings: HttpSettings = airlockHttpSettings
      override def isUserAuthorizedForRequest(request: S3Request, user: User)(implicit id: RequestId): Boolean = true
      override val storageS3Settings: StorageS3Settings = StorageS3Settings(testSystem)
      override val kafkaSettings: KafkaSettings = KafkaSettings(testSystem)

      override def areCredentialsActive(awsRequestCredential: AwsRequestCredential)(implicit id: RequestId): Future[Option[User]] =
        Future(Some(User(UserRawJson("userId", Set("group"), "accesskey", "secretkey"))))

      def createLineageFromRequest(httpRequest: HttpRequest, userSTS: User, clientIPAddress: RemoteAddress)(implicit id: RequestId): Future[Done] = Future.successful(Done)
    }
    proxy.startup.map { binding =>
      try testCode(getAmazonS3(
        awsSignerType = awsSignerType,
        authority = Authority(Host(binding.localAddress.getAddress), binding.localAddress.getPort)
      ))
      finally proxy.shutdown()
    }
  }

  // TODO: Expand with different signer types
  val awsSignerTypes = List(
    "S3SignerType" //,
    //      SignerFactory.NO_OP_SIGNER,
    //      SignerFactory.QUERY_STRING_SIGNER,
    //      SignerFactory.VERSION_FOUR_SIGNER,
    //      SignerFactory.VERSION_FOUR_UNSIGNED_PAYLOAD_SIGNER,
    //      SignerFactory.VERSION_THREE_SIGNER
  )

  awsSignerTypes.foreach { awsSignerType =>

    "S3 Proxy" should {
      s"proxy with $awsSignerType" that {

        "list the current buckets" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            assert(sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
          }
        }

        "create and remove a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          val testBucket = "createbuckettest"
          sdk.createBucket(testBucket)
          assert(sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
          sdk.deleteBucket(testBucket)
          assert(!sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
        }

        "list files in a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            val testKey = "keyListFiles"

            sdk.putObject(testBucket, testKey, "content")
            val resultV2 = sdk.listObjectsV2(testBucket).getObjectSummaries.asScala.toList.map(_.getKey)
            val result = sdk.listObjects(testBucket).getObjectSummaries.asScala.toList.map(_.getKey)

            assert(resultV2.contains(testKey))
            assert(result.contains(testKey))
          }
        }

        "check if bucket exists" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            assert(sdk.doesBucketExistV2(testBucket))
          }
        }

        "head on bucket object" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKeyFile = "keyPutFileByFile"

              sdk.putObject(testBucket, testKeyFile, new File(filename))

              assert(sdk.doesObjectExist(testBucket, testKeyFile))
            }
          }
        }


        "put, get and delete an object from a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKeyContent = "keyPutFileByContent"
              val testKeyFile = "keyPutFileByFile"
              val testContent = "content"

              // PUT
              sdk.putObject(testBucket, testKeyContent, testContent)
              sdk.putObject(testBucket, testKeyFile, new File(filename))

              // GET
              val checkContent = sdk.getObjectAsString(testBucket, testKeyContent)
              assert(checkContent == testContent)
              val keys1 = getKeysInBucket(sdk, testBucket)
              List(testKeyContent, testKeyFile).map(k => assert(keys1.contains(k)))

              // DELETE
              sdk.deleteObject(testBucket, testKeyContent)
              val keys2 = getKeysInBucket(sdk, testBucket)
              assert(!keys2.contains(testKeyContent))
            }
          }
        }

        "copy object with REPLACE metadata on object" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKeyFile = "keyPutFileByFile"
              val destinationKey = "sdkcopy"
              val ownerValue = "itTest"
              sdk.putObject(testBucket, testKeyFile, new File(filename))

              val copyRequest = new CopyObjectRequest(testBucket, testKeyFile, testBucket, destinationKey)
              val newMetadata = new ObjectMetadata()
              newMetadata.addUserMetadata("Owner", ownerValue)
              copyRequest.setMetadataDirective("REPLACE")
              copyRequest.setNewObjectMetadata(newMetadata)

              sdk.copyObject(copyRequest)
              assert(sdk.getObjectMetadata(testBucket, destinationKey).getUserMetadata.containsValue(ownerValue))
            }
          }
        }

        "put objects with special characters in object names" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKeyFileWithDolar = "keywith$.txt"
              val testKeyFileWithHash = "keywith#.txt"
              val testKeyFileWithExclamation = "keywith!.txt"
              val testKeyFileWithSpace = "keywith space.txt"
              val testKeyFileWithBracket = "keywith[bracket].txt"
              val testKeyFileWithPlus = "keywith+.txt"
              val testKeyFileWithCurly = "keywith(curly).txt"

              val dolarUploadResult = sdk.putObject(testBucket, testKeyFileWithDolar, new File(filename))
              val hashUploadResult = sdk.putObject(testBucket, testKeyFileWithHash, new File(filename))
              val exclamationUploadResult = sdk.putObject(testBucket, testKeyFileWithExclamation, new File(filename))
              val spaceUploadResult = sdk.putObject(testBucket, testKeyFileWithSpace, new File(filename))
              val bracketUploadResult = sdk.putObject(testBucket, testKeyFileWithBracket, new File(filename))
              val plusUploadResult = sdk.putObject(testBucket, testKeyFileWithPlus, new File(filename))
              val curlyUploadResult = sdk.putObject(testBucket, testKeyFileWithCurly, new File(filename))


              assert(sdk.doesObjectExist(testBucket, testKeyFileWithDolar))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithHash))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithExclamation))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithSpace))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithBracket))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithPlus))
              assert(sdk.doesObjectExist(testBucket, testKeyFileWithCurly))

              assert(!dolarUploadResult.getETag.isEmpty)
              assert(!hashUploadResult.getETag.isEmpty)
              assert(!exclamationUploadResult.getETag.isEmpty)
              assert(!spaceUploadResult.getETag.isEmpty)
              assert(!bracketUploadResult.getETag.isEmpty)
              assert(!plusUploadResult.getETag.isEmpty)
              assert(!curlyUploadResult.getETag.isEmpty)
            }
          }
        }

        // TODO: Fix proxy for copyObject function
//        "check if object can be copied" in withS3SdkToMockProxy(awsSignerType) { sdk =>
//          withBucket(sdk) { testBucket =>
//            withBucket(sdk) { tragetBucket =>
//              withFile(1024 * 1024) { filename =>
//                sdk.putObject(testBucket, "keyCopyOrg", new File(filename))
//                sdk.copyObject(testBucket, "keyCopyOrg", tragetBucket, "keyCopyDest")
//
//                val keys1 = getKeysInBucket(sdk, testBucket)
//                assert(!keys1.contains("keyCopyOrg"))
//                val keys2 = getKeysInBucket(sdk, "newbucket")
//                assert(keys2.contains("keyCopyDest"))
//              }
//            }
//          }
//      }

        "put a 1MB file in a bucket (multi part upload)" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKey = "keyMultiPart1MB"
              doMultiPartUpload(sdk, testBucket, filename, testKey)
              val objectKeys = getKeysInBucket(sdk, testBucket)
              assert(objectKeys.contains(testKey))
            }
          }
        }

        // TODO: reenable, sometimes fails still with `upload canceled` error
        //        "put a 100MB file in a bucket (multi part upload)" in {
        //          doMultiPartUpload("file100mb.test", "keyMultiPart100MB")
        //
        //          val objectKeys = getKeysInBucket()
        //          assert(objectKeys.contains("keyMultiPart100MB"))
        //        }

        // TODO: Fix 1GB multi part upload
        //        "put a 1GB file in a bucket (multi part upload)" in {
        //          doMultiPartUpload("file1gb.test", "keyMultiPart1GB")
        //
        //          val objectKeys = getKeysInBucket()
        //          assert(objectKeys.contains("keyMultiPart1GB"))
        //        }
      }
    }
  }
}
