/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheusdata.upcloud

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import com.morpheusdata.upcloud.services.UpcloudApiService

class UpcloudPlugin extends Plugin {

    @Override
    String getCode() {
        return 'upcloud'
    }

    @Override
    void initialize() {
        this.setName("Upcloud")
        this.registerProvider(new UpcloudCloudProvider(this,this.morpheus))
        this.registerProvider(new UpcloudProvisionProvider(this,this.morpheus))
        this.registerProvider(new UpcloudBackupProvider(this,this.morpheus))
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        //nothing to do for now
    }

    def getAuthConfig(Cloud cloud) {
        def rtn = [:]

        if(!cloud.accountCredentialLoaded) {
            AccountCredential accountCredential
            try {
                if(!cloud.account?.id || !cloud.owner?.id) {
                    log.debug("cloud account or owner id is missing, loading cloud object")
                    cloud = morpheus.services.cloud.get(cloud.id)
                }
                accountCredential = morpheus.services.accountCredential.loadCredentials(cloud)
            } catch(e) {
                // If there is no credential on the cloud, then this will error
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }

        log.debug("AccountCredential loaded: $cloud.accountCredentialLoaded, Data: $cloud.accountCredentialData")

        def username
        if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
            username = cloud.accountCredentialData['username']
        } else {
            username = cloud.configMap.username
        }

        def password
        if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
            password = cloud.accountCredentialData['password']
        } else {
            password = cloud.configMap.password
        }

        rtn.username = username
        rtn.password = password
        rtn.apiUrl = UpcloudApiService.upCloudEndpoint
        return rtn
    }
}
