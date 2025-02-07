package com.morpheusdata.upcloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.util.logging.Slf4j

@Slf4j
class UpcloudBackupExecutionProvider implements BackupExecutionProvider {

	UpcloudPlugin plugin
	MorpheusContext morpheusContext

	UpcloudBackupExecutionProvider(UpcloudPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}
	
	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Add additional configurations to a backup. Morpheus will handle all basic configuration details, this is a
	 * convenient way to add additional configuration details specific to this backup provider.
	 * @param backupModel the current backup the configurations are applied to.
	 * @param config the configuration supplied by external inputs.
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup creation process.
	 */
	@Override
	ServiceResponse configureBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Validate the configuration of the backup. Morpheus will validate the backup based on the supplied option type
	 * configurations such as required fields. Use this to either override the validation results supplied by the
	 * default validation or to create additional validations beyond the capabilities of option type validation.
	 * @param backupModel the backup to validate
	 * @param config the original configuration supplied by external inputs.
	 * @param opts optional parameters used for
	 * @return a {@link ServiceResponse} object. The errors field of the ServiceResponse is used to send validation
	 * results back to the interface in the format of {@code errors['fieldName'] = 'validation message' }. The msg
	 * property can be used to send generic validation text that is not related to a specific field on the model.
	 * A ServiceResponse with any items in the errors list or a success value of 'false' will halt the backup creation
	 * process.
	 */
	@Override
	ServiceResponse validateBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Create the backup resources on the external provider system.
	 * @param backupModel the fully configured and validated backup
	 * @param opts additional options used during backup creation
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a success value of 'false' will indicate the
	 * creation on the external system failed and will halt any further backup creation processes in morpheus.
	 */
	@Override
	ServiceResponse createBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the backup resources on the external provider system.
	 * @param backupModel the backup details
	 * @param opts additional options used during the backup deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the results of a backup execution on the external provider system.
	 * @param backupResultModel the backup results details
	 * @param opts additional options used during the backup result deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		def rtn = ServiceResponse.success()
		try {
			def config = backupResult.getConfigMap()
			def snapshotList = config.snapshotList
			if(snapshotList?.size() > 0) {
				def workload = morpheus.async.workload.get(backupResult.containerId).blockingGet()
				if(workload) {
					def instance = morpheus.async.instance.get(workload.instanceId).blockingGet()
					def server = morpheus.async.computeServer.get(workload.serverId).blockingGet()
					def cloud = morpheus.async.cloud.get(server.zoneId).blockingGet()
					//auth config
					def authConfig = UpcloudApiService.getAuthConfig(cloud)
					//delete
					def deleteSuccess = true
					snapshotList?.each { snapshot ->
						def deleteResult = UpcloudApiService.removeStorage(authConfig, snapshot.storageId)
						deleteSuccess = deleteSuccess && deleteResult.success
					}
					if(!deleteSuccess) {
						rtn.success = false
					}
				}

			}
		} catch(e) {
			log.error("An Exception Has Occurred",e)
			rtn.success = false
		}
		return rtn
	}

	/**
	 * A hook into the execution process. This method is called before the backup execution occurs.
	 * @param backupModel the backup details associated with the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the execution preperation. A success value
	 * of 'false' will halt the execution process.
	 */
	@Override
	ServiceResponse prepareExecuteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Provide additional configuration on the backup result. The backup result is a representation of the output of
	 * the backup execution including the status and a reference to the output that can be used in any future operations.
	 * @param backupResultModel
	 * @param opts
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.
	 */
	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Initiate the backup process on the external provider system.
	 * @param backup the backup details associated with the backup execution.
	 * @param backupResult the details associated with the results of the backup execution.
	 * @param executionConfig original configuration supplied for the backup execution.
	 * @param cloud cloud context of the target of the backup execution
	 * @param computeServer the target of the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution. A success value
	 * of 'false' will halt the execution process.
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		try {
			log.debug("backupConfig container: {}", rtn)

			Workload workload = morpheus.services.workload.get(rtn.containerId)
			Instance instance = morpheus.services.instance.get(workload.instanceId)
			def snapshotName = "${instance.name}.${workload.id}.${System.currentTimeMillis()}".toString()

			//sync for non windows
			if(computeServer.serverOs?.platform != 'windows') {
				morpheus.executeCommandOnServer(computeServer, 'sync')
			}

			//auth config
			def authConfig = plugin.getAuthConfig(cloud)
			def serverStatus = UpcloudApiService.waitForServerStatus(authConfig, computeServer.externalId, 'started')

			//create the backup for each disk
			def snapshotResults = []
			def snapshotSuccess = true
			computeServer.volumes?.each { StorageVolume volume ->
				if(volume.externalId) {
					def snapshotConfig = [snapshotName:snapshotName]
					def snapShotStartResult = UpcloudApiService.waitForStorageStatus(authConfig, volume.externalId, 'online', [maxAttempts:360, retryInterval:(1000l * 10l)])
					def snapshotResult = UpcloudApiService.createSnapshot(authConfig, volume.externalId, snapshotConfig)
					if(snapShotStartResult.success == false && !snapshotResult.msg) {
						snapshotResult.msg = "Storage state invalid."
					}
					snapshotSuccess = snapShotStartResult.success && snapshotResult.success && snapshotSuccess
					snapshotResults << [volume: volume, snapshotResult: snapshotResult]
				}
			}
			log.info("backup complete: {}", snapshotResults)
			if(snapshotSuccess == true) {
				def totalSize = (snapshotResults.snapshotResult?.data?.storage?.sum() ?: 0) * 1024

				def snapshots = []
				snapshotResults?.each {
					def storageResults = it.snapshotResult.data?.storage
					StorageVolume volume = it.volume
					if(storageResults?.uuid)
						snapshots << [root: volume.rootVolume, sizeInGb: storageResults.size, originId:storageResults.origin, storageId:storageResults.uuid]
				}

				rtn.success = true
				rtn.data.backupResult.status = BackupStatusUtility.IN_PROGRESS
				rtn.data.backupResult.backupResultId = rtn.backupResultId
				rtn.data.backupResult.backupSizeInMb = totalSize
				rtn.data.backupResult.providerType = 'upcloud'
				rtn.data.backupResult.setConfigProperty("snapshots", snapshots)
				rtn.data.updates = true
			} else {
				//error
				def errorSnapshots = snapshotResults.findAll{ it.snapshotResult.success != true && it.snapshotResult.msg }
				def errorMessage = errorSnapshots?.collect{ it.snapshotResult.msg }?.join('\n') ?: 'unknown error creating snapshots'
				rtn.data.backupResult.backupSizeInMb = 0
				rtn.data.backupResult.errorOutput = errorMessage
				rtn.data.backupResult.status = BackupStatusUtility.FAILED
				rtn.data.updates = true
			}
			if(snapshotSuccess == true && opts.inline) {
				snapshotResults.each { snapshotResult ->
					def snapshotStatus = UpcloudApiService.waitForStorageStatus(authConfig, snapshotResult.data?.storage?.uuid, 'online')
				}
				refreshBackupResult(backupResult)
			}
		} catch(e) {
			log.error("executeBackup: ${e}", e)
			rtn.msg = e.getMessage()
			rtn.data.backupResult.errorOutput = "Failed to execute backup".encodeAsBase64()
			rtn.data.backupResult.backupSizeInMb = 0
			rtn.data.backupResult.status = BackupStatusUtility.FAILED
			rtn.data.updates = true
		}
		return rtn
	}

	/**
	 * Periodically call until the backup execution has successfully completed. The default refresh interval is 60 seconds.
	 * @param backupResult the reference to the results of the backup execution including the last known status. Set the
	 *                     status to a canceled/succeeded/failed value from one of the {@link BackupStatusUtility} values
	 *                     to end the execution process.
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.n
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		try {
			log.debug("syncBackupResult {}", backupResult)
			def config = backupResult.getConfigMap()
			def snapshotList = config.snapshotList

			if (snapshotList?.size() > 0) {
				def workload = morpheus.services.workload.get(backupResult.containerId)
				def instance = morpheus.services.instance.get(workload.instanceId)
				def server = morpheus.computeServer.get(workload.serverId)
				def cloud = morpheus.services.cloud.get(server.zoneId)
				//auth config
				def authConfig = UpcloudPlugin.getAuthConfig(cloud)
				def statusResults = []
				def statusSuccess = true
				def statusComplete = true
				def totalSize = 0
				snapshotList?.each { snapshot ->
					def statusResult = UpcloudApiService.getStorageDetails(authConfig, snapshot.storageId)
					statusResults << statusResult
					statusSuccess = statusResult.success && statusSuccess
					statusComplete = statusComplete && (statusResult.data?.storage?.state == 'online')
					totalSize += (statusResult.data?.storage?.size?.toLong() ?: 0)
				}
				if (statusSuccess == true && statusComplete == true) {
					rtn.data.updates = true
					rtn.data.backupResult.status = "SUCCEEDED"
					rtn.data.backupResult.endDate = new Date()
					rtn.data.backupResult.setConfigProperty("sizeInGb", totalSize)
					rtn.data.backupResult.sizeInMb = (long) (totalSize * 1024l)

					def startDate = DateUtility.parseDate(backupResult.startDate)
					def endDate = rtn.data.backupResult.endDate
					if (startDate && endDate)
						rtn.data.backupResult.durationMillis = endDate.time - startDate.time
				}
			}
		} catch (Exception e) {
			log.error("refreshBackupResult error: {}", e, e)
		}
		return rtn
	}
	
	/**
	 * Cancel the backup execution process without waiting for a result.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution cancellation.
	 */
	@Override
	ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Extract the results of a backup. This is generally used for packaging up a full backup for the purposes of
	 * a download or full archive of the backup.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup extraction.
	 */
	@Override
	ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

}		
