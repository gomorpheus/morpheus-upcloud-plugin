package com.morpheusdata.upcloud.util

import groovy.json.JsonOutput
import groovy.util.logging.Commons
import org.apache.http.HttpEntity
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpHead
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils

@Commons
class UpcloudComputeUtility {
    static upcloudApiVersion = '1.3'
    static requestTimeout = 300000 //5 minutes?

    static getServerDetail(Map authConfig, String serverId) {
        def rtn = [success:false]
        try {
            def callOpts = [:]
            def callPath = "/server/${serverId}"
            def callResults = callApi(authConfig, callPath, callOpts, 'GET')
            if(callResults.success == true) {
                rtn.data = callResults.data
                rtn.server = rtn.data?.server
                rtn.volumes = getVmVolumes(rtn.data?.server?.'storage_devices'?.'storage_device')
                rtn.networks = getVmNetworks(rtn.data?.server?.'ip_addresses'?.'ip_address')
                rtn.success = true
            } else {
                rtn.success = false
            }
        } catch (e) {
            log.error "Error on getServerDetail: ${e}", e
            rtn.success = false
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

    static callApi(Map authConfig, String path, Map opts = [:], String method) {
        def rtn = [success:false, headers:[:]]
        def httpClient =  createHttpClient(authConfig + [timeout:requestTimeout])
        def apiUrl = authConfig.apiUrl
        def username = authConfig.username
        def password = authConfig.password
        def apiVersion = authConfig.apiVersion ?: upcloudApiVersion
        log.debug("calling to: ${apiUrl}; path: ${apiVersion}${path}, opts: ${JsonOutput.prettyPrint(JsonOutput.toJson(opts + [password: '*******']))}")
        try {
            def apiPath = "${apiVersion}${path}"
            def fullUrl = "${apiUrl}/${apiPath}"
            URIBuilder uriBuilder = new URIBuilder(fullUrl)
            if(opts.query) {
                opts.query?.each { k, v ->
                    uriBuilder.addParameter(k, v)
                }
            }
            HttpRequestBase request
            switch(method) {
                case 'HEAD':
                    request = new HttpHead(uriBuilder.build())
                    break
                case 'PUT':
                    request = new HttpPut(uriBuilder.build())
                    break
                case 'POST':
                    request = new HttpPost(uriBuilder.build())
                    break
                case 'GET':
                    request = new HttpGet(uriBuilder.build())
                    break
                case 'DELETE':
                    request = new HttpDelete(uriBuilder.build())
                    break
                default:
                    throw new Exception('method was not specified')
            }
            String creds = "${username}:${password}".toString()
            String authHeader = "Basic ${creds.getBytes().encodeBase64().toString()}".toString()
            request.addHeader(HttpHeaders.AUTHORIZATION, authHeader)
            if(!opts.headers || !opts.headers['Content-Type']) {
                request.addHeader('Content-Type', 'application/json')
            }
            opts.headers?.each { k, v ->
                request.addHeader(k, v)
            }
            if(opts.body) {
                HttpEntityEnclosingRequestBase postRequest = (HttpEntityEnclosingRequestBase)request
                postRequest.setEntity(new StringEntity(opts.body.encodeAsJSON().toString()))
            }
            CloseableHttpResponse response = httpClient.execute(request)
            try {
                if(response.getStatusLine().getStatusCode() <= 399) {
                    rtn.success = true
                    HttpEntity entity = response.getEntity()
                    if(entity) {
                        def jsonString = EntityUtils.toString(entity)
                        if(jsonString) {
                            rtn.data = new groovy.json.JsonSlurper().parseText(jsonString)
                        }
                        log.debug "SUCCESS data to ${fullUrl}, results: ${JsonOutput.prettyPrint(jsonString ?: '')}"
                    } else {
                        rtn.data = null
                    }
                    rtn.success = true
                } else {
                    if(response.getEntity()) {
                        def jsonString = EntityUtils.toString(response.getEntity())
                        rtn.data = new groovy.json.JsonSlurper().parseText(jsonString)
                        log.debug "FAILURE data to ${fullUrl}, results: ${JsonOutput.prettyPrint(jsonString ?: '')}"
                    }
                    rtn.success = false
                    rtn.errorCode = response.getStatusLine().getStatusCode()?.toString()
                    rtn.msg = rtn.data?.error?.'error_message' ?: rtn.data?.error
                    log.warn("error: ${rtn.errorCode} - ${rtn.data}")
                }
            } catch(ex) {
                log.error("Error occurred processing the response for ${fullUrl}", ex)
            } finally {
                if(response) {
                    response.close()
                }
            }
        } catch(e) {
            log.error("${e} : stack: ${e.printStackTrace()}")
            rtn.msg = e.message
        }
        return rtn
    }
}
