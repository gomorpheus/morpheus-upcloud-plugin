package com.morpheusdata.upcloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.core.backup.DefaultBackupJobProvider
import com.morpheusdata.core.backup.MorpheusBackupProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class UpcloudBackupProvider extends MorpheusBackupProvider {

	BackupJobProvider backupJobProvider;

	UpcloudBackupProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)

		UpcloudBackupTypeProvider backupTypeProvider = new UpcloudBackupTypeProvider(plugin, morpheus)
		plugin.registerProvider(backupTypeProvider)
		addScopedProvider(backupTypeProvider, "upcloud", null)
	}
}