package com.morpheusdata.upcloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.response.BackupRestoreResponse
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.response.ServiceResponse;
import com.morpheusdata.model.BackupRestore;
import com.morpheusdata.model.BackupResult;
import com.morpheusdata.model.Backup;
import com.morpheusdata.model.Instance
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.util.logging.Slf4j

@Slf4j
class UpcloudBackupRestoreProvider implements BackupRestoreProvider {

	Plugin plugin
	MorpheusContext morpheusContext

	UpcloudBackupRestoreProvider(Plugin plugin, MorpheusContext morpheusContext) {
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
	 * Add additional configurations to a backup restore. Morpheus will handle all basic configuration details, this is a
	 * convenient way to add additional configuration details specific to this backup restore provider.
	 * @param backupResultModel backup result to be restored
	 * @param config the configuration supplied by external inputs
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse configureRestoreBackup(BackupResult backupResult, Map config, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Build the configuration for the restored instance.
	 * @param backupResultModel backup result to be restored
	 * @param instanceModel the instance the backup was created from, if it still exists. Retained backups will not have a reference to the instance.
	 * @param restoreConfig the restore configuration generated by morpheus.
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResult, Instance instanceModel, Map restoreConfig, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Verify the backup restore is valid. Generally used to check if the backup and instance are both in a state
	 * compatible for executing the restore process.
	 * @param backupResultModel backup result to be restored
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse validateRestoreBackup(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Get restore options to configure the restore wizard. Although the {@link com.morpheusdata.core.backup.BackupProvider } and
	 * {@link com.morpheusdata.core.backup.BackupTypeProvider} supply configuration, there may be situations where the instance
	 * configuration will determine which options need to be presented in the restore wizard.
	 * <p>
	 * Available Restore options:
	 * 		<ul>
	 * 		 	<li>
	 * 		 	    restoreExistingEnabled (Boolean) -- determines the visibility of the restore to existing option
	 * 		 	</li>
	 * 		 	<li>
	 * 		 	  	restoreNewEnabled (Boolean) -- determines the visibility of the restore to new option
	 * 		 	</li>
	 * 		 	<li>
	 * 		 	  	name (String) -- default name of the restored instance
	 * 		 	</li>
	 * 		 	<li>
	 * 		 		hostname (String) -- default hostname of the restored instance
	 * 		 	</li>
	 * 		</ul>
	 *
	 * @param backupModel the backup
	 * @param opts optional parameters
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse getRestoreOptions(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Execute the backup restore on the external system
	 * @param backupRestoreModel restore to be executed
	 * @param backupResultModel refernce to the backup result
	 * @param backupModel reference to the backup associated with the backup result
	 * @param opts optional parameters
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse restoreBackup(BackupRestore backupRestore, BackupResult backupResult, Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Periodically check for any updates to an in-progress restore. This method will be executed every 60 seconds for
	 * the restore while the restore has a status of `START_REQUESTED` or `IN_PROGRESS`. Any other status will indicate
	 * the restore has completed and does not need to be refreshed. The primary use case for this method is long-running
	 * restores to avoid consuming resources during the restore process.
	 * @param backupRestore the running restore
	 * @param backupResult backup result referencing the backup to be restored
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup restore process.
	 */
	@Override
	ServiceResponse refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		log.debug("syncBackupRestoreResult backupRestore: ${backupRestore}")
		ServiceResponse<BackupRestoreResponse> rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		def externalId = backupRestore.externalId
		if(externalId) {
			//load it
			def server = morpheus.async.computeServer.find(
				new DataQuery().withFilter("account", "=", backupRestore.account)
						.withFilter("externalId", "=", externalId)
			).blockingGet()

			if(server) {
				//get status of server
				def authConfig = UpcloudApiService.getAuthConfig(server.cloud)
				def serverDetail = UpcloudApiService.getServerDetail(authConfig, server.externalId)
				if(serverDetail.success == true && serverDetail?.server?.state == 'started') {
					//running again
					rtn.data.backupRestore.endDate = new Date()
					rtn.data.backupRestore.status = "SUCCEEDED"
					def startDate = rtn.data.backupRestore.startDate
					def endDate = rtn.data.backupRestore.endDate
					if(startDate && endDate)
						rtn.data.backupRestore.duration = endDate.time - startDate.time

					rtn.data.updates = true
				}
			}
		}
		return rtn
	}
}
