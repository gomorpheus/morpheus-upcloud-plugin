package com.morpheusdata.upcloud.util

import com.morpheusdata.*
import groovy.json.JsonOutput
import groovy.util.logging.Commons
import org.apache.http.*
import org.apache.http.client.*
import org.apache.http.client.methods.*
import org.apache.http.client.utils.*
import org.apache.http.entity.*
import org.apache.http.util.*
import com.morpheusdata.upcloud.services.UpcloudApiService

@Commons
class UpcloudStatusUtility {
    static testConnection(Map authConfig) {
        def rtn = [success:false, invalidLogin:false]
        try {
            def results = UpcloudApiService.listZones(authConfig)
            rtn.success = results.success
        } catch(e) {
            log.error("testConnection to upcloud: ${e}")
        }
        return rtn
    }

    static getVmVolumes(storageDevices) {
        def rtn = []
        try {
            storageDevices?.eachWithIndex { storageDevice, index ->
                if(storageDevice.type == 'disk') {
                    def newDisk = [address:storageDevice.address, size:storageDevice.'storage_size',
                                   description:storageDevice.'storage_title', name:storageDevice.'storage_title',
                                   type:'disk', storageId:storageDevice.storage, index:index, deviceName:storageDevice.address]
                    rtn << newDisk
                }
            }
        } catch(e) {
            log.error("getVmVolumes error: ${e}")
        }
        return rtn
    }

    static getVmNetworks(networkDevices) {
        def rtn = []
        try {
            def counter = 0
            networkDevices?.each { networkDevice ->
                def newNic = [access:networkDevice.access, family:networkDevice.family, address:networkDevice.address,
                              row:counter]
                rtn << newNic
                counter++
            }
        } catch(e) {
            log.error("getVmNetworks error: ${e}")
        }
        return rtn
    }

    static validateServerConfig(Map opts=[:]){
        def rtn = [success: true, errors: []]
        if(opts.containsKey('nodeCount') && !opts.nodeCount){
            rtn.errors += [field:'nodeCount', msg:'Cannot be blank']
            rtn.errors += [field:'config.nodeCount', msg:'Cannot be blank']
        }
        rtn.success = (rtn.errors.size() == 0)
        return rtn
    }
}
