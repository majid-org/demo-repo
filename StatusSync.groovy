import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import services.replication.PreparedHttpClient

/**
Usage:

Add this snippet below to the end of your "Outgoing sync(data filter)":

StatusSync.send(replica, issue)

--------------------------------

Add the snippet below to the end of your "Incoming sync for new issues(create processor)":

* If statuses are the same on both sides:

return CreateIssue.create(
  replica,
  issue,
  relation,
  nodeHelper,
  issueBeforeScript,
  remoteReplica,
  traces,
  blobMetadataList){
    StatusSync.receive(
     true,
     [ : ],
     replica,
     issue,
     nodeHelper,
     httpClient
    )
   return null
}

* If the statuses are different, specify the status mapping

return CreateIssue.create(
  replica,
  issue,
  relation,
  nodeHelper,
  issueBeforeScript,
  remoteReplica,
  traces,
  blobMetadataList){
    StatusSync.receive(
     [
         "Source Status A" : "Dest Status A",
         "Source Status B" : "Dest Status B",
         "Source Status C" : "Dest Status C",
     ],
     replica,
     issue,
     nodeHelper,
     httpClient
    )
   return null
}
--------------------------------

Add the snippet below to the end of your "Incoming sync for existing issues(change processor)":

* If statuses are the same on both sides:

StatusSync.receive(
 true,
 [ : ],
 [ : ],
 [ : ],
 [ : ],
 replica,
 issue,
 nodeHelper,
 httpClient
)

* If the statuses are different, specify the status mapping

StatusSync.receive(
 [
     "Source Status A" : "Dest Status A",
     "Source Status B" : "Dest Status B",
     "Source Status C" : "Dest Status C",
 ],
 [ : ],
 [ : ],
 [ : ],
 replica,
 issue,
 nodeHelper,
 httpClient
)
--------------------------------
 */
class StatusSync {
    //ERRORS
    static def relationLevelError2 = { String msg, Throwable cause ->
        new IllegalStateException(msg, cause)
    }
    static def relationLevelError = { String msg ->
        new IllegalStateException(msg)
    }
    static def issueLevelError(String msg) {
        new com.exalate.api.exception.IssueTrackerException(msg)
    }
    static def issueLevelError2(String msg, Throwable e) {
        new com.exalate.api.exception.IssueTrackerException(msg, e)
    }

    // SCALA HELPERS
    private static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    private static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }
    private static <T> scala.Option<T> none() { scala.Option$.MODULE$.<T>empty() }
    @SuppressWarnings("GroovyUnusedDeclaration")
    private static <T> scala.Option<T> none(Class<T> evidence) { scala.Option$.MODULE$.<T>empty() }
    private static <L, R> scala.Tuple2<L, R> pair(L l, R r) { scala.Tuple2$.MODULE$.<L, R>apply(l, r) }
    private static <T> scala.collection.Seq<T> seq(T ... ts) {
        def list = Arrays.asList(ts)
        def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
        scalaBuffer.toSeq()
    }

    // SERVICES AND EXALATE API
    private static play.api.inject.Injector getInjector() {
        InjectorGetter.getInjector()
    }
    /**
     * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that IJCloudGeneralSettingsRepository might have
     * a different classname such as IJCloudGneeralSettingsPersistence, so we will load the class dinamycally and catching an exception if Exalate is running
     * 5.3 or lower version
     */
    private static def getGeneralSettings() {
        def classLoader = this.getClassLoader()
        def gsp
        try {
            gsp = getInjector().instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsRepository"))
        } catch(ClassNotFoundException exception) {
            gsp = getInjector().instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence"))
        }
        def gsOpt = await(gsp.get())
        def gs = orNull(gsOpt)
        gs
    }
    private static String getJiraCloudUrl() {
        final def gs = getGeneralSettings()

        def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
        final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
        jiraCloudUrl
    }


    static Closure<BasicHubIssue> defaultOnNoStatusFound = { String desiredStatusName, Map<String, Object> wf, BasicHubIssue issue ->
        throw new com.exalate.api.exception.IssueTrackerException(
                "Can not find status `" + desiredStatusName + "` " +
                        "in workflow `" + wf.name + ". " +
                        "Please review whether `" + wf.name + "` is the correct workflow for the issue `" + issue.key + "`."
        )
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue send(BasicHubIssue replica, BasicHubIssue issue) {
        sendStatus(replica, issue)
    }
    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue sendStatus(BasicHubIssue replica, BasicHubIssue issue) {
        replica.status = issue.status
        replica
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(Map<String, String> workflowMapping,
                                                                             BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        receiveStatus(workflowMapping, [:], [:], [:], replica, issue, nodeHelper, httpClient)
    }
    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveStatus(Map<String, String> workflowMapping,
                                                                             BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        receiveStatus(false, workflowMapping, [:], [:], [:], replica, issue, nodeHelper, httpClient)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(Map<String, String> workflowMapping,
                                                                       Map<String, Object> projectWorkflowSchemeMapping,
                                                                       Map<Object, String> workflowSchemeMapping,
                                                                       Map<String, String> workflowDetailsMapping,
                                                                       BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        receiveStatus(workflowMapping, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient)
    }
    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveStatus(Map<String, String> workflowMapping,
                                                                             Map<String, Object> projectWorkflowSchemeMapping,
                                                                             Map<Object, String> workflowSchemeMapping,
                                                                             Map<String, String> workflowDetailsMapping, BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        receiveStatus(false, workflowMapping, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient)
    }

    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(boolean useRemoteStatusByDefault,
                                                                       Map<String, String> workflowMapping,
                                                                       Map<String, Object> projectWorkflowSchemeMapping,
                                                                       Map<Object, String> workflowSchemeMapping,
                                                                       Map<String, String> workflowDetailsMapping, BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        receiveStatus(useRemoteStatusByDefault, workflowMapping, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient)
    }
    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receiveStatus(boolean useRemoteStatusByDefault,
                                                                             Map<String, String> workflowMapping,
                                                                             Map<String, Object> projectWorkflowSchemeMapping,
                                                                             Map<Object, String> workflowSchemeMapping,
                                                                             Map<String, String> workflowDetailsMapping,
                                                                             BasicHubIssue replica,
                                                                             BasicHubIssue issue,
                                                                             nodeHelper,
                                                                             PreparedHttpClient httpClient) {
        receive(defaultOnNoStatusFound, useRemoteStatusByDefault, workflowMapping, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient)

    }
    static com.exalate.basic.domain.hubobject.v1.BasicHubIssue receive(Closure<BasicHubIssue> onNoStatusFoundFn,
                                                                             boolean useRemoteStatusByDefault,
                                                                             Map<String, String> workflowMapping,
                                                                             Map<String, Object> projectWorkflowSchemeMapping,
                                                                             Map<Object, String> workflowSchemeMapping,
                                                                             Map<String, String> workflowDetailsMapping,
                                                                             BasicHubIssue replica,
                                                                             BasicHubIssue issue,
                                                                             nodeHelper,
                                                                             PreparedHttpClient httpClient) {
        try {

            def desiredStatusName = (workflowMapping.find { k,_ -> k.equalsIgnoreCase(replica.status?.name) }?.value)
            if (useRemoteStatusByDefault && desiredStatusName == null) {
                desiredStatusName = replica.status?.name
            }
            transitionTo(onNoStatusFoundFn, desiredStatusName, projectWorkflowSchemeMapping, workflowSchemeMapping, workflowDetailsMapping, replica, issue, nodeHelper, httpClient)
        } catch (com.exalate.api.exception.IssueTrackerException ite) {
            throw ite
        } catch (Exception e) {
            throw new com.exalate.api.exception.IssueTrackerException(e)
        }
        issue
    }

    static transitionTo(Closure<BasicHubIssue> onNoStatusFoundFn, @javax.annotation.Nullable String desiredStatusName,
                        Map<String, Object> projectWorkflowSchemeMapping,
                        Map<Object, String> workflowSchemeMapping,
                        Map<String, String> workflowDetailsMapping, BasicHubIssue replica, BasicHubIssue issue, nodeHelper, PreparedHttpClient httpClient) {
        def username = "user"
        def password = "pass"
        ({
            try {
                def jIssue = issue
                if (issue.id == null) {
                    throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `StatusSync.receiveStatus(...)` """.toString())
                }
                def localExIssueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)

                final def gs = generalSettings

                def getWorkflowSchemeIdForProject = { String projectKey, String currentStatus, String targetStatus ->
                    def wfsId = projectWorkflowSchemeMapping[projectKey]
                    if (wfsId != null) {
                        return wfsId
                    }
                    def response
                    try {
                        //noinspection GroovyAssignabilityCheck
                        response = await(httpClient
                                .ws()
                                .url(jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey)
                                .withAuth(username, password, play.api.libs.ws.WSAuthScheme.BASIC$.MODULE$)
                                .withMethod("GET")
                                .execute()
                        )
                    } catch (Exception e) {
                        throw issueLevelError2(
                                "Unable to get workflow scheme for project `" + projectKey + "` on behalf of user `" + username + "` using a private REST API, please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
                                        "\nAuth: " + username + ":" + password +
                                        "\nError: " + e.message,
                                e
                        )
                    }
                    if (401 == response.status()) {
                        throw issueLevelError("It looks you need to perform more than one transition to get from current status `"+currentStatus+"` to target status `"+targetStatus+"`. " +
                                "Unfortunately in order for exalate to calculate the right transition path to do that, more configuration will be required on your script. " +
                                "Check https://docs.idalko.com/exalate/display/ED/Status+synchronization+on+Jira+cloud+with+multiple+transitions+on+a+single+sync for more details")
//                        throw issueLevelError("Can not get workflow scheme for project `"+ projectKey +"`. " +
//                                "\nKnown projects / workflow schemes: `"+ projectWorkflowSchemeMapping +"`" +
//                                "\nPlease contact Exalate Support. " +
//                                "\nAlso failed to get it through REST API: "+
//                                "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
//                                "\nAuth: " + username +
//                                "\nResponse: " + response.body()
//                        )
                    }
                    if (200 != response.status()) {
                        throw issueLevelError(
                                "Failed to get workflow scheme for project `" + projectKey + "` on behalf of user `" + username + "` using a private REST API (status " + response.status() + "), please contact Exalate Support: " +
                                        "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflowscheme/" + projectKey +
                                        "\nAuth: " + username + ":" + password +
                                        "\nResponse: " + response.body()
                        )
                    }
                    def s = new groovy.json.JsonSlurper()

                    def json
                    try {
                        json = s.parseText(response.body())
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow scheme json from private REST API, please contact Exalate Support: " + response.body(), e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow scheme json from private REST API has unrecognized structure, please contact Exalate Support: " + response.body())
                    }
                    (json as Map<String, Object>).id as Long
                }

                def getWorkflowScheme = { Object workflowSchemeId ->
                    def jsonStrInternal = workflowSchemeMapping[workflowSchemeId]
                    if (jsonStrInternal == null) {
                        def response
                        try {
                            //noinspection GrUnresolvedAccess
                            response = await(httpClient
                                    .ws()
                                    .url(jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId.toString())
                                    .withAuth(username, password, play.api.libs.ws.WSAuthScheme.BASIC$.MODULE$)
                                    .withMethod("GET")
                                    .execute()
                            )
                        } catch (Exception e) {
                            throw issueLevelError2(
                                    "Unable to get workflow scheme by id `" + workflowSchemeId + "`, please contact Exalate Support: " +
                                            "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId +
                                            "\nAuth: " + username + ":" + password +
                                            "\nError: " + e.message,
                                    e
                            )
                        }
                        if (response.status() == 401) {
                            throw issueLevelError("It looks you need to perform more than one transition to get from current status `"+currentStatus+"` to target status `"+targetStatus+"`. " +
                                "Unfortunately in order for exalate to calculate the right transition path to do that, more configuration will be required on your script. " +
                                "Check https://docs.idalko.com/exalate/display/ED/Status+synchronization+on+Jira+cloud+with+multiple+transitions+on+a+single+sync for more details")
//                            throw issueLevelError("Can not get details for workflow scheme by id `"+ workflowSchemeId +"`. " +
//                                    "\nKnown projects / workflow schemes: `"+ workflowSchemeMapping +"`" +
//                                    "\nPlease contact Exalate Support."+
//                                    "\nAlso failed to get it through REST API: "+
//                                    "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId +
//                                    "\nAuth: " + username +
//                                    "\nResponse: " + response.body()
//                            )
                        }
                        if (response.status() != 200) {
                            throw issueLevelError(
                                    "Failed to get workflow scheme by id `" + workflowSchemeId + "` (status " + response.status() + "), please contact Exalate Support: " +
                                            "\nRequest: GET " + jiraCloudUrl + "/rest/api/2/workflowscheme/" + workflowSchemeId +
                                            "\nAuth: " + username + ":" + password +
                                            "\nResponse: " + response.body()
                            )
                        }
                        jsonStrInternal = response.body()
                    }


                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(jsonStrInternal)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow scheme json, please contact Exalate Support: " + jsonStrInternal, e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow scheme json has unrecognized structure, please contact Exalate Support: " + jsonStrInternal)
                    }
//                    if (!(((json as Map<String, Object>).mappings instanceof List<Map<String, Object>>) || ((json as Map<String, Object>).issueTypeMappings instanceof Map<String, String>))) {
//                        throw issueLevelError("Workflow scheme mappings json has unrecognized structure, please contact Exalate Support: " + jsonStrInternal)
//                    }
                    json
                }

                def getWorkflowName = { Object workflowSchemeId, String issueTypeId ->
                    def workflowSchemeJson = getWorkflowScheme(workflowSchemeId)

                    def wfName = workflowSchemeJson.mappings ?
                            (workflowSchemeJson.mappings.find { wfMapping -> wfMapping.issueTypes.any { it == issueTypeId } } ?: workflowSchemeJson.mappings.find { it.default == true })?.name :
                            workflowSchemeJson.issueTypeMappings[issueTypeId] ?: workflowSchemeJson.defaultWorkflow
                    wfName as String
                }

                def getWorkflowDetailsForProject = { String workflowName, String projectKey ->
                    def projectToWorkflowDetailsMapping = workflowDetailsMapping[workflowName]

                    def jsonStr
                    if (projectToWorkflowDetailsMapping != null) {
                        jsonStr = projectToWorkflowDetailsMapping
                    } else {
                        def response
                        try {
                            response = await(httpClient
                                    .ws()
                                    .url(jiraCloudUrl + "/rest/projectconfig/1/workflow")
                                    .withQueryString(seq(
                                    pair("workflowName", workflowName),
                                    pair("projectKey", projectKey)
                            ))
                                    .withAuth(username, password, play.api.libs.ws.WSAuthScheme.BASIC$.MODULE$)
                                    .withMethod("GET")
                                    .execute()
                            )
                        } catch (Exception e) {
                            throw issueLevelError2(
                                    "Unable to get workflow details for name `" + workflowName + "` for project `" + projectKey + "` on behalf of user `" + username + "`, please contact Exalate Support: " +
                                            "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflow?workflowName=" + workflowName + "&projectKey=" + projectKey +
                                            "\nAuth: " + username + ":" + password +
                                            "\nError: " + e.message,
                                    e
                            )
                        }
                        if (401 == response.status()) {
                            throw issueLevelError("Can not get project to workflow details mapping for workflow name`"+ workflowName +"`. " +
                                    "\nKnown workflow name to project to details mapping: `"+ workflowDetailsMapping +"`" +
                                    "\nPlease contact Exalate Support."+
                                    "\nAlso failed to get it through REST API: "+
                                    "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflow?workflowName=" + workflowName + "&projectKey=" + projectKey +
                                    "\nAuth: " + username +
                                    "\nResponse: " + response.body()
                            )
                        }
                        if (200 != response.status()) {
                            throw issueLevelError(
                                    "Failed to get workflow details for name `" + workflowName + "` for project `" + projectKey + "` on behalf of user `" + username + "` (status " + response.status() + "), please contact Exalate Support: " +
                                            "\nRequest: GET " + jiraCloudUrl + "/rest/projectconfig/1/workflow?workflowName=" + workflowName + "&projectKey=" + projectKey +
                                            "\nAuth: " + username + ":" + password +
                                            "\nResponse: " + response.body()
                            )
                        }
                        jsonStr = response.body()
                    }


                    def s = new groovy.json.JsonSlurper()

                    def json
                    try {
                        json = s.parseText(jsonStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse workflow details json, please contact Exalate Support: " + jsonStr, e)
                    }
                    if (!(json instanceof Map<String, Object>)) {
                        throw issueLevelError("Workflow details json has unrecognized structure, please contact Exalate Support: " + jsonStr)
                    }
                    if (!((json as Map<String, Object>).sources instanceof List<Map<String, Object>>)) {
                        throw issueLevelError("Workflow details sources json has unrecognized structure, please contact Exalate Support: " + jsonStr)
                    }
                    json as Map<String, Object>
                }


                def getTransitionsForIssue = { String issueKeyStr ->
                    def response
                    try {
                        response = await(await(httpClient.authenticate(
                                none(),
                                httpClient
                                        .ws()
                                        .url(jiraCloudUrl + "/rest/api/2/issue/" + issueKeyStr + "/transitions")
                                        .withQueryString(seq(pair("skipRemoteOnlyCondition", "true")))
                                        .withMethod("GET"),
                                gs
                        )).get())
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to get transitions for issue, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/transitions" +
                                "\nError: " + e.message, e)
                    }
                    if (response.status() != 200) {
                        throw issueLevelError("Can not get transitions for issue (status " + response.status() + "), please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/transitions" +
                                "\nResponse: " + response.body())
                    }
                    def resultStr = response.body()
                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(resultStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the get transitions for issue json, please contact Exalate Support: " + resultStr, e)
                    }
                    if (!(json instanceof Map)) {
                        throw issueLevelError("Get transitions for issue json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    if (!(json.transitions instanceof List)) {
                        throw issueLevelError("Get transitions for issue .transitions json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    json as Map<String, Object>
                }
                def getGlobalTransitionsViaIssue = { String issueKeyStr ->
                    def tsResponse = getTransitionsForIssue(issueKeyStr)
                    def ts = tsResponse.transitions as List<Map<String, Object>>;
                    ts
                            .findAll { t -> t.isGlobal }
                            .collect { t ->
                        [
                                "id"    : t.id as String,
                                "name"  : t.name as String,
                                "to"    : [
                                        "id"  : t.to.id as String,
                                        "name": t.to.name as String
                                ],
                                "global": true
                        ]
                    }
                }
                def getDirectTransitionsViaIssue = { String issueKeyStr ->
                    def tsResponse = getTransitionsForIssue(issueKeyStr)
                    def ts = tsResponse.transitions as List<Map<String, Object>>;
                    ts
                            .collect { t ->
                        [
                                "id"    : t.id as String,
                                "name"  : t.name as String,
                                "to"    : [
                                        "id"  : t.to.id as String,
                                        "name": t.to.name as String
                                ],
                                "global": t.isGlobal
                        ]
                    }
                }
                def getWorkflow = { String workflowName, String projectKey, String issueKeyStr ->
                    def json = getWorkflowDetailsForProject(workflowName, projectKey);
                    def sources = (json as Map<String, Object>).sources as List<Map<String, Object>>;
                    def transitions = sources
                            .collect { src ->
                        if (!(src.fromStatus instanceof Map<String, Object>)) {
                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + src.fromStatus + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                        }
                        def fromStatus = src.fromStatus as Map<String, Object>
                        if (!(fromStatus.id instanceof String)) {
                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + fromStatus + "` id `" + fromStatus.id + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                        }
                        if (!(fromStatus.name instanceof String)) {
                            throw issueLevelError("Workflow details sources `" + src + "` from status `" + fromStatus + "` name `" + fromStatus.name + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                        }
                        if (!(src.targets instanceof List)) {
                            throw issueLevelError("Workflow details sources `" + src + "` targets `" + src.targets + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                        }
                        def targets = src.targets as List<Map<String, Object>>;
                        def ts = targets.collect { target ->
                            if (!(target instanceof Map<String, Object>)) {
                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                            }
                            if (!(target.toStatus instanceof Map<String, Object>)) {
                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + target.toStatus + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                            }
                            def toStatus = target.toStatus as Map<String, Object>;
                            if (!(toStatus.id instanceof String)) {
                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + toStatus + "` id `" + toStatus.id + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                            }
                            if (!(toStatus.name instanceof String)) {
                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` toStatus `" + toStatus + "` name `" + toStatus.name + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                            }
                            if (!(target.transitionName instanceof String)) {
                                throw issueLevelError("Workflow details sources `" + src + "` target `" + target + "` transitionName `" + target.transitionName + "` json has unrecognized structure, please contact Exalate Support: " + response.body())
                            }
                            [
                                    "name"  : target.transitionName,
                                    "from"  : [
                                            "id"  : fromStatus.id,
                                            "name": fromStatus.name,
                                    ],
                                    "to"    : [
                                            "id"  : toStatus.id,
                                            "name": toStatus.name,
                                    ],
                                    "global": false
                            ]
                        }
                        ts
                    }
                    .flatten()
                    def allTransitions = transitions + getGlobalTransitionsViaIssue(issueKeyStr)
                    [
                            "name"       : workflowName,
                            "transitions": allTransitions,
                            "steps"      : allTransitions.inject([]) { List<Map<String, Object>> result, t ->
                                def stepsToAddToResult = t.global ? [t.to] : [t.from, t.to]
                                stepsToAddToResult.inject(result) { r, s ->
                                    def curStep = r.find { step -> step.id == s.id }
                                    if (curStep != null) {
                                        // the step is already in the result steps list
                                        if (t?.from?.id == s.id && !curStep.transitions?.any { it.name == t.name }) {
                                            // if the transition is from this step
                                            curStep.transitions += t
                                        }
                                    } else {
                                        // the step is not in the result steps list
                                        r += [
                                                "id"         : s.id,
                                                "name"       : s.name,
                                                "transitions": t?.from?.id == s.id ? [t] : []
                                        ]
                                    }
                                    r
                                }
                            }
                    ]
                }
                def transition = { com.exalate.basic.domain.BasicIssueKey zissueKey ->
                    { Map<String, Object> t, String transitionExecUserKey, Map<String, Object> fields ->
                        if (t.id == null) {
                            def currentlyAvailableTransitions = getTransitionsForIssue(zissueKey.URN).transitions as List<Map<String, Object>>;
                            def zTransition = currentlyAvailableTransitions.find { Map<String, Object> aTransition ->
                                aTransition.name as String == t.name as String &&
                                        aTransition.to?.id as Long == t.to.id as Long
                            }
                            if (zTransition == null) {
                                throw issueLevelError("Failing to transition because the transition found by algorithm `" + t.name + "` is no longer available for current step `" + t.from.name + "` (" + t.from.id + "). Please contact Exalate Support" +
                                        "\nAvailable transitions:" + currentlyAvailableTransitions)
                            }
                            t.id = zTransition.id
                        }
                        def json = [
                                "transition": [
                                        "id": t.id
                                ],
                        ] as Map<String, Object>
                        if (fields != null) {
                            json.fields = fields
                        }
                        def jsonStr = groovy.json.JsonOutput.toJson(json)
                        def response
                        try {
                            //noinspection GroovyAssignabilityCheck
                            httpClient.post("/rest/api/2/issue/"+ zissueKey.URN + "/transitions", jsonStr)
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to transition issue `" + zissueKey.URN + "`, please contact Exalate Support:  \n" +
                                    "POST " + jiraCloudUrl + "/rest/api/2/issue/" + zissueKey.URN + "\nBody: " + jsonStr + "\nError Message:" + e.message, e)
                        }
                    }
                }
                def getTransitions = { String statusName, Map<String, Object> wf ->
                    def step = wf.steps.find { s -> s.name.equalsIgnoreCase(statusName) }
                    if (step == null) {
                        throw issueLevelError("Can not find step `" + statusName + "`. Available steps: `" + wf.steps.collect { s -> s.name + " (" + s.id + ")" } + "`. Create it or review the transitioning script.")
                    }
                    step.transitions
                }
                def getGlobalTransitions = { Map<String, Object> wf ->
                    wf
                            .transitions
                            .findAll { t -> t.global }
                }
                def getAllTransitions = { String statusName, List<String> visitedStates, Map<String, Object> wf ->
                    (getTransitions(statusName, wf) + getGlobalTransitions(wf))
                            .findAll { t -> !visitedStates.any { it.equalsIgnoreCase(t.to.name) } }
                }
                def getTransitionPathInternal
                getTransitionPathInternal = { transitionWithParent ->
                    if (!transitionWithParent) return null;
                    if (transitionWithParent.parentTransition == null) {
                        return [transitionWithParent.transition]
                    }
                    getTransitionPathInternal(transitionWithParent.parentTransition) + [transitionWithParent.transition]
                }
                def getPathsFromAtoBInternal = { String currentStatusName, String targetStatusName, Map<String, Object> wf ->
                    if (currentStatusName.equalsIgnoreCase(targetStatusName)) {
                        return null
                    }
                    def q = [] as Queue<Map<String, Object>>;
                    def visitedStates = [] as List<String>;
                    def allFirstTransitions = getAllTransitions(currentStatusName, visitedStates, wf) as List<Map<String, Object>>;
                    for (aTransition in allFirstTransitions) {
                        def nextStatus = aTransition.to.name as String
                        def transitionWithParent = ["transition": aTransition, "parentTransition": null]
                        if (nextStatus.equalsIgnoreCase(targetStatusName)) {
                            return transitionWithParent
                        }
                        if (aTransition.from) {
                            visitedStates.add(aTransition.from.name as String)
                        }
                        q.add(transitionWithParent)
                    }
                    //solution -> every state has a from transition property
                    while (!q.isEmpty()) {
                        def transitionWithParent = q.remove()
                        def nextStatus = transitionWithParent.transition.to.name as String
                        def childTransitions = getAllTransitions(nextStatus, visitedStates, wf) as List<Map<String, Object>>;
                        for (childTransition in childTransitions) {
                            def nextChildStatus = childTransition.to.name as String
                            if (targetStatusName.equalsIgnoreCase(nextChildStatus)) {
                                return ["transition": childTransition, "parentTransition": transitionWithParent]
                            }
                            q.add(["transition": childTransition, "parentTransition": transitionWithParent])
                        }
                        visitedStates.add(nextStatus)
                    }
                }
                def getEditIssueMeta = { String issueKeyStr ->
//    GET /rest/api/2/issue/{issueIdOrKey}/editmeta
                    def response
                    try {
                        //noinspection GrUnresolvedAccess
                        response = await(await(httpClient.authenticate(
                                none(),
                                httpClient
                                        .ws()
                                        .url(jiraCloudUrl + "/rest/api/2/issue/" + issueKeyStr + "/editmeta")
                                        .withMethod("GET"),
                                gs
                        )).get())
                    } catch (Exception e) {
                        throw issueLevelError2("Unable to get edit meta for issue, please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/editmeta" +
                                "\nError: " + e.message, e)
                    }
                    if (response.status() != 200) {
                        throw issueLevelError("Can not get edit meta for issue (status " + response.status() + "), please contact Exalate Support: " +
                                "\nRequest: GET /rest/api/2/issue/" + issueKeyStr + "/editmeta" +
                                "\nResponse: " + response.body())
                    }
                    def resultStr = response.body()
                    def s = new groovy.json.JsonSlurper()
                    def json
                    try {
                        json = s.parseText(resultStr)
                    } catch (Exception e) {
                        throw issueLevelError2("Can not parse the get edit meta for issue json, please contact Exalate Support: " + resultStr, e)
                    }

                    /*
            {"fields":{
                "summary":{"required":true,"schema":{"type":"string","system":"summary"},"name":"Summary","key":"summary","operations":["set"]},
                "issuetype":{"required":true,"schema":{"type":"issuetype","system":"issuetype"},"name":"Issue Type","key":"issuetype","operations":[],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10002","id":"10002","description":"A task that needs to be done.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10318&avatarType=issuetype","name":"Task","subtask":false,"avatarId":10318},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10001","id":"10001","description":"Created by Jira Agile - do not edit or delete. Issue type for a user story.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10315&avatarType=issuetype","name":"Story","subtask":false,"avatarId":10315},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10004","id":"10004","description":"A problem which impairs or prevents the functions of the product.","iconUrl":"https://tsleft.atlassian.net/secure/viewavatar?size=xsmall&avatarId=10303&avatarType=issuetype","name":"Bug","subtask":false,"avatarId":10303},{"self":"https://tsleft.atlassian.net/rest/api/2/issuetype/10000","id":"10000","description":"Created by Jira Software - do not edit or delete. Issue type for a big user story that needs to be broken down.","iconUrl":"https://tsleft.atlassian.net/images/icons/issuetypes/epic.svg","name":"Epic","subtask":false}]},
                "components":{"required":false,"schema":{"type":"array","items":"component","system":"components"},"name":"Component/s","key":"components","operations":["add","set","remove"],"allowedValues":[]},
                "description":{"required":false,"schema":{"type":"string","system":"description"},"name":"Description","key":"description","operations":["set"]},
                "customfield_10064":{"required":false,"schema":{"type":"string","custom":"com.atlassian.jira.plugin.system.customfieldtypes:textfield","customId":10064},"name":"Remote Issue ID","key":"customfield_10064","operations":["set"]},
                "customfield_10010":{"required":false,"schema":{"type":"array","items":"string","custom":"com.pyxis.greenhopper.jira:gh-sprint","customId":10010},"name":"Sprint","key":"customfield_10010","operations":["set"]},
                "fixVersions":{"required":false,"schema":{"type":"array","items":"version","system":"fixVersions"},"name":"Fix Version/s","key":"fixVersions","operations":["set","add","remove"],"allowedValues":[]},
                "priority":{"required":false,"schema":{"type":"priority","system":"priority"},"name":"Priority","key":"priority","operations":["set"],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/priority/1","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/highest.svg","name":"Highest","id":"1"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/2","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/high.svg","name":"High","id":"2"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/3","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/medium.svg","name":"Medium","id":"3"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/4","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/low.svg","name":"Low","id":"4"},{"self":"https://tsleft.atlassian.net/rest/api/2/priority/5","iconUrl":"https://tsleft.atlassian.net/images/icons/priorities/lowest.svg","name":"Lowest","id":"5"}]},
                "labels":{"required":false,"schema":{"type":"array","items":"string","system":"labels"},"name":"Labels","key":"labels","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/1.0/labels/suggest?query=","operations":["add","set","remove"]},
                "customfield_10008":{"required":false,"schema":{"type":"any","custom":"com.pyxis.greenhopper.jira:gh-epic-link","customId":10008},"name":"Epic Link","key":"customfield_10008","operations":["set"]},
                "attachment":{"required":false,"schema":{"type":"array","items":"attachment","system":"attachment"},"name":"Attachment","key":"attachment","operations":[]},
                "issuelinks":{"required":false,"schema":{"type":"array","items":"issuelinks","system":"issuelinks"},"name":"Linked Issues","key":"issuelinks","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/2/issue/picker?currentProjectId=&showSubTaskParent=true&showSubTasks=true&currentIssueKey=LPROJ-9&query=","operations":["add"]},
                "comment":{"required":false,"schema":{"type":"comments-page","system":"comment"},"name":"Comment","key":"comment","operations":["add","edit","remove"]},
                "assignee":{"required":false,"schema":{"type":"user","system":"assignee"},"name":"Assignee","key":"assignee","autoCompleteUrl":"https://tsleft.atlassian.net/rest/api/latest/user/assignable/search?issueKey=LPROJ-9&username=","operations":["set"]},
                "resolution":{"required":false,"schema":{"type":"resolution","system":"resolution"},"name":"Resolution","key":"resolution","operations":["set"],"allowedValues":[{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10000","name":"Done","id":"10000"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10001","name":"Won't Do","id":"10001"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10002","name":"Duplicate","id":"10002"},{"self":"https://tsleft.atlassian.net/rest/api/2/resolution/10003","name":"Cannot Reproduce","id":"10003"}]}
            }}
                */
                    if (!(json instanceof Map)) {
                        throw issueLevelError("Get edit meta for issue json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    if (!(json.fields instanceof Map)) {
                        throw issueLevelError("Get edit meta for issue `.fields` json has unrecognized structure, please contact Exalate Support: " + resultStr)
                    }
                    json as Map<String, Object>
                }

                // applying the resolution
                if (
                issue.resolution?.name != replica.resolution?.name &&
                        (getEditIssueMeta(jIssue.key).fields as Map<String, Object>)
                                .values()
                                .any { meta ->
                            meta.key == "resolution" &&
                                    meta.schema?.system == "resolution"
                        }
                ) {
                    if (replica.resolution?.name == null) {
                        issue.resolution = null
                    } else {
                        def resolution = nodeHelper.getResolution(replica.resolution?.name)
                        if (resolution == null) {
                            throw issueLevelError("Could not find resolution `" + replica.resolution?.name + "`. Please create one and resolve the error or contact Exalate Supprt.")
                        }
                        issue.resolution = resolution
                    }
                }
                if (desiredStatusName != null && !jIssue.status.name.equalsIgnoreCase(desiredStatusName)) {
                    def directTrans = getDirectTransitionsViaIssue(issue.key)
                    def directTransToDesiredStatus = directTrans.find { t -> desiredStatusName.equalsIgnoreCase(t?.to?.name) }
                    if (directTransToDesiredStatus != null) {
                        transition(localExIssueKey)(directTransToDesiredStatus as Map<String, Object>, null, null as Map<String, Object>)
                        def newStatusHub = new com.exalate.basic.domain.hubobject.v1.status.BasicHubStatus()
                        newStatusHub.name = desiredStatusName
                        issue.status = newStatusHub
                        return
                    }
                    def wfSchemeId = getWorkflowSchemeIdForProject(issue.project.key, jIssue.status.name, desiredStatusName)
                    def wfName = getWorkflowName(wfSchemeId, issue.type.id)
                    def wf = getWorkflow(wfName, issue.project.key, jIssue.key)
                    if (!wf.steps?.any { step -> desiredStatusName.equalsIgnoreCase(step?.name) }) {
//                        throw issueLevelError(
//                                "Can not find status `" + desiredStatusName + "` " +
//                                        "in workflow `" + wf.name + ". " +
//                                        "Please review whether `" + wf.name + "` is the correct workflow for the issue `" + jIssue.key + "`."
//                        )
                        def onStatusFoundResult = onNoStatusFoundFn(desiredStatusName, wf, jIssue)
                        if (onStatusFoundResult == null) {
                            // don't try to sync status, since we've indicated that we don't want to change anything
                            return
                        }
                    }
                    def shortestPath = getTransitionPathInternal(getPathsFromAtoBInternal(jIssue.status.name, desiredStatusName, wf))
                    if (shortestPath == null) {
                        throw issueLevelError(
                                "Can not find path from `" + jIssue.status.name + "` to `" + desiredStatusName + "` " +
                                        "in workflow `" + wf.name + ". " +
                                        "Please review whether `" + wf.name + "` is the correct workflow for the issue `" + jIssue.key + "`."
                        )
                    }
                    shortestPath.each { t ->
                        transition(localExIssueKey)(t as Map<String, Object>, null, null as Map<String, Object>)
                    }
                    def newStatusHub = new com.exalate.basic.domain.hubobject.v1.status.BasicHubStatus()
                    newStatusHub.name = desiredStatusName
                    issue.status =newStatusHub
                }
            } catch (com.exalate.api.exception.IssueTrackerException ite) {
                throw ite
            } catch (Exception e) {
                throw new com.exalate.api.exception.IssueTrackerException(e)
            }
        })()
        issue
    }
}
