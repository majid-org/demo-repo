import com.exalate.api.domain.IIssueKey
import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.hubobject.v1_2.IHubProject
import com.exalate.api.exception.IssueTrackerException
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import services.jcloud.hubobjects.NodeHelper

/**
 Usage:
 Add the snippet below to the end of your "Outgoing sync":


 Epic.sendEpicFirst() // pollForEpicLinkUpdatesInterval = null
 --------------------------------
 Add the snippet below to the end of your "Incoming sync":

 Epic.receive()
 --------------------------------

 doesn't break on subtask
 shouldn't try to set epic link on subtask
 doesn't try to set epic color and status


 * */

class Epic {

    static log = org.slf4j.LoggerFactory.getLogger("com.exalate.scripts.Epic")

    static BasicHubIssue sendInAnyOrder(Long pollForEpicLinkUpdatesInterval = null) {
        return (new EpicCtx()).sendInAnyOrder(pollForEpicLinkUpdatesInterval)
    }
    static BasicHubIssue sendEpicFirst(Long pollForEpicLinkUpdatesInterval = null) {
        return (new EpicCtx()).sendEpicFirst()
    }

    static BasicHubIssue send() {
        return (new EpicCtx()).sendEpicFirst()
    }

    static def receive() {
        return (new EpicCtx()).receive()
    }

    static def issueLevelError(String msg) {
        new com.exalate.api.exception.IssueTrackerException(msg)
    }

    static class EpicCtx {
        // SCALA HELPERS
        static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
        static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }
        static <P1, R> scala.Function1<P1, R> fn(Closure<R> closure) {
            (scala.runtime.AbstractFunction1<P1, R>)new scala.runtime.AbstractFunction1() {
                @Override
                Object apply(Object p1) {
                    return closure.call(p1)
                }
            }
        }
        static <P1, P2, R> scala.Function2<P1, P2, R> fn2(Closure<R> closure) {
            (scala.runtime.AbstractFunction2<P1, P2, R>)new scala.runtime.AbstractFunction2() {
                @Override
                Object apply(Object p1, Object p2) {
                    return closure.call(p1, p2)
                }
            }
        }
        static <T> scala.Option<T> none() { scala.Option$.MODULE$.<T>empty() }
        @SuppressWarnings("GroovyUnusedDeclaration")
        static <T> scala.Option<T> none(Class<T> evidence) { scala.Option$.MODULE$.<T>empty() }
        static <T1, T2> scala.Tuple2<T1, T2> pair(T1 l, T2 r) { scala.Tuple2$.MODULE$.<T1, T2>apply(l, r) }
        static <T> scala.collection.Seq<T> seq(T ... ts) {
            def list = Arrays.asList(ts)
            def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
            scalaBuffer.toSeq()
        }
        static <T> scala.collection.Seq<T> seqPlus (scala.collection.Seq<T> tsLeft, T ... tsRight) {
            def list = Arrays.asList(tsRight)
            def scalaBuffer = scala.collection.JavaConversions.asScalaBuffer(list)
            scala.collection.Seq$.MODULE$
                    .newBuilder()
                    .$plus$plus$eq(tsLeft)
                    .$plus$plus$eq(scalaBuffer)
                    .result()
        }
        static <P> scala.collection.Seq<P> paginateInternal(Integer offset, Integer limit, scala.collection.Seq<P> result, scala.Function2<Integer, Integer, P> nextPageFn, scala.Function1<P, Integer> getTotalFn) {
            def page = nextPageFn.apply(offset, limit)
            def total = getTotalFn.apply(page)
            def last = total < limit
            def newResult = seqPlus(result, page)
            if (last) {
                newResult
            } else {
                paginateInternal(offset + limit, limit, newResult, nextPageFn, getTotalFn)
            }
        }
        static <P, I> List<I> paginate(Integer limit, scala.Function2<Integer, Integer, P> nextPageFn, scala.Function1<P, List<I>> getItemsFn) {
            def getTotalFn = AgileSync.<P, Integer> fn { P p -> getItemsFn.apply(p).size() }
            scala.collection.Seq<P> resultSeq = paginateInternal(0, limit, AgileSync.<P>seq(), nextPageFn, getTotalFn)
            List<P> pages = scala.collection.JavaConversions.bufferAsJavaList(resultSeq.toBuffer())
            def items = pages
                    .collect { P p -> getItemsFn.apply(p) }
                    .flatten()
            items
        }

        // SERVICES AND EXALATE API
        static play.api.inject.Injector getInjector() {
            InjectorGetter.getInjector()
        }
        /**
         * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that IJCloudGeneralSettingsRepository might have
         * a different classname such as IJCloudGneeralSettingsPersistence, so we will load the class dinamycally and catching an exception if Exalate is running
         * 5.3 or lower version
         */
        static def getGeneralSettings() {
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
        static String getJiraCloudUrl() {
            final def gs = getGeneralSettings()

            def removeTailingSlash = { String str -> str.trim().replace("/+\$", "") }
            final def jiraCloudUrl = removeTailingSlash(gs.issueTrackerUrl)
            jiraCloudUrl
        }
        Object getHttpClient() {
            def context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()
            if (!context) {
                context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
            }
            if (!context) {
                context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
            }
            if (!context) {
                throw new com.exalate.api.exception.IssueTrackerException(""" No context for executing external script Epic.groovy. Please contact Exalate Support.""".toString())
            }
            context.httpClient
        }


        // JIRA API
        static List<Map<String, Object>> getFieldsJson(httpClient) {
            //"com.pyxis.greenhopper.jira:gh-epic-link"

            def fieldsResponse
            try {
                fieldsResponse = await(httpClient.thisJira("/rest/api/2/field", "GET", null, null).get())
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get the fields json, please contact Exalate Support: " + e.message, e)
            }
            if (fieldsResponse.status() != 200) {
                throw new IllegalStateException("Can not get fields (status "+ fieldsResponse.status() +"), please contact Exalate Support: "+ fieldsResponse.body())
            }
            groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
            /*
            [..., {"id":"customfield_10990","key":"customfield_10990","name":"Epic Link","custom":true,"orderable":true,"navigable":true,"searchable":true,"clauseNames":["cf[10990]","Epic Link"],"schema":{"type":"any","custom":"com.pyxis.greenhopper.jira:gh-epic-link","customId":10990}, ...}]
             */
            def fieldsJson
            try {
                fieldsJson = s.parseText(fieldsResponse.body())
            } catch (Exception e) {
                throw new IllegalStateException("Can not parse fields json, please contact Exalate Support: " + fieldsResponse.body(), e)
            }
            if (!(fieldsJson instanceof List)) {
                throw new IllegalStateException("Fields json has unrecognized strucutre, please contact Exalate Support: " + fieldsResponse.body())
            }
            fieldsJson as List<Map<String, Object>>
        }

        static List<Map<String, Object>> getFieldScreens(log, httpClient, fieldId) {
            def fieldScreensResponse
            def path = String.format("/rest/api/3/field/%s/screens", fieldId)
            try {
                fieldScreensResponse = await(httpClient.thisJira(path, "GET", null, null).get())
            } catch (Exception e) {
                log.error(String.format("Http Client throwed an exception while getting screens for field %s. Error message: %s"), e)
                throw new IllegalStateException(String.format("Unable to get the field screens for field %s, please contact Exalate Support: %s", fieldId, e.message))
            }
            if (fieldScreensResponse.status() != 200) {
                throw new IllegalStateException(String.format("Can not get screen for field %s. Jira Cloud Http Response status: %s and body: %s. Please contact Exalate Support", fieldId, fieldScreensResponse.status(), fieldScreensResponse.body()))
            }
            groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()

            def fieldScreensJson
            try {
                fieldScreensJson = s.parseText(fieldScreensResponse.body())
            } catch (Exception e) {
                log.error(String.format("Json parser throwed exception while parsing Jira Cloud HTTP response body.\n HTTP Request: %s \n HTTP Response: \n    Status: %s \n     Body: %s", path, fieldScreensResponse.status(), fieldScreensResponse.body()), e)
                throw new IllegalStateException(String.format("Problems while getting screens for field %s, please contact Exalate Support: ",fieldScreensResponse.body()))
            }

            def fieldScreensJsonObject = fieldScreensJson as Map<String,Object>

            fieldScreensJsonObject.values
        }

        static def searchFn(httpClient) { return { String jql ->
            final def gs = generalSettings
            //noinspection GroovyAssignabilityCheck
            def foundIssues = paginate(
                    50,
                    fn2 { Integer offset, Integer limit ->
                        def searchResponse
                        try {
                            searchResponse = await(await(httpClient.authenticate(
                                    none(),
                                    httpClient
                                            .ws()
                                            .url(jiraCloudUrl+"/rest/api/2/search")
                                            .withQueryString(seq(
                                                    pair("jql", jql),
                                                    pair("startAt", offset as String),
                                                    pair("maxResults", limit as String),
                                                    pair("fields", "id,key")
                                            ))
                                            .withMethod("GET"),
                                    gs
                            )).get())
                        } catch (Exception e) {
                            throw issueLevelError2("Unable to search, please contact Exalate Support: " +
                                    "\nRequest: GET /rest/api/2/search?jql="+ jql +"&startAt="+ offset +"&maxResults="+ limit +"&fields=id,key" +
                                    "\nError: " + e.message, e)
                        }
                        if (searchResponse.status() != 200) {
                            throw issueLevelError("Can not search (status "+ searchResponse.status() +"), please contact Exalate Support: " +
                                    "\nRequest: GET /rest/api/2/search?jql="+ jql +"&startAt="+ offset +"&maxResults="+ limit +"&fields=id,key"+
                                    "\nResponse: "+ searchResponse.body())
                        }
                        def searchResult = searchResponse.body()
                        groovy.json.JsonSlurper s = new groovy.json.JsonSlurper()
                        def searchResultJson
                        try {
                            searchResultJson = s.parseText(searchResult)
                        } catch (Exception e) {
                            throw issueLevelError2("Can not parse the search json, please contact Exalate Support: " + searchResult, e)
                        }

                        /*
                        {
                          "expand": "names,schema",
                          "startAt": 0,
                          "maxResults": 50,
                          "total": 1,
                          "issues": [
                            {
                              "expand": "",
                              "id": "10001",
                              "self": "http://www.example.com/jira/rest/api/2/issue/10001",
                              "key": "HSP-1"
                            }
                          ],
                          "warningMessages": [
                            "The value 'splat' does not exist for the field 'Foo'."
                          ]
                        }
                        */
                        if (!(searchResultJson instanceof Map)) {
                            throw issueLevelError("Issue search json has unrecognized structure, please contact Exalate Support: " + searchResult)
                        }
                        searchResultJson as Map<String, Object>
                    },
                    fn { Map<String, Object> page ->
                        if (!(page.issues instanceof List)) {
                            throw issueLevelError("Issue Search json has unrecognized structure inside each page, please contact Exalate Support: " + page)
                        }
                        page.issues as List<Map<String, Object>>
                    }
            )
            foundIssues.collect { story -> [
                    "id": story.id as Long,
                    "key": story.urn as String
            ]}
        } }

        static def isSubtask(httpClient, issueTypeId) {
            return new JiraClient(httpClient).http("GET", "/rest/api/3/issuetype/${issueTypeId}",[:], null, [:], { response ->
                if (response.code >= 300 && response.code != 404) {
                    throw new com.exalate.api.exception.IssueTrackerException("Failed to perform the request GET  rest/api/3/issuetype/${issueTypeId} (status ${response.code}), and body was: \n\"${response.body}\"\nPlease contact Exalate Support: ".toString() + response.body)
                }
                if (response.code == 404) {
                    return null
                }
                def responseStr = response.body as String
                responseStr ?
                        new groovy.json.JsonSlurper().parseText(responseStr) as Map<String, Object>
                        : null;
            })?.subtask
        }

        BasicHubIssue sendInAnyOrder(Long pollForEpicLinkUpdatesInterval) {
            send(pollForEpicLinkUpdatesInterval, { _, replica, issue, connection ->
                sendEpicInAnyOrder(pollForEpicLinkUpdatesInterval, replica, issue, connection)
            })
        }

        BasicHubIssue sendEpicFirst(Long pollForEpicLinkUpdatesInterval) {
            send(pollForEpicLinkUpdatesInterval, { _, replica, issue, connection ->
                sendEpicHavingEpicFirst(pollForEpicLinkUpdatesInterval, replica, issue, connection)
            })
        }

        BasicHubIssue send(Long pollForEpicLinkUpdatesInterval, Closure<BasicHubIssue> sendEpicFn) {
            def context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()

            final def replica = context.replica
            final def issue = context.issue
            final IConnection connection = context.connection

            replica.id = issue.id
            sendEpicFn(pollForEpicLinkUpdatesInterval, replica, issue, connection)
        }

        def receive() {
            def context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
            if (!context) {
                context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
            }
            if (!context) {
                throw new com.exalate.api.exception.IssueTrackerException(""" No context for executing external script Epic.groovy. Please contact Exalate Support.""".toString())
            }

            final def replica = context.replica
            final BasicHubIssue issue = context.issue
            final NodeHelper nodeHelper = context.nodeHelper
            final def syncRequest = context.syncRequest
            final def connection = context.connection
            final def issueBeforeScript = context.issueBeforeScript
            final def traces = context.traces
            final def blobMetadataList = context.blobMetadataList
            final def firstSync = context.firstSync

            def issueTypeId = issue.type?.id ?: nodeHelper.getIssueType(issue.typeName, issue.projectKey)?.id
            if (issueTypeId == null){
                throw new com.exalate.api.exception.IssueTrackerException("${issue.typeName} is not a valid type for project: ${issue.projectKey}")
            }
            def isSubtask = isSubtask(httpClient, issueTypeId)

            if (isSubtask){
                //if its a subtask we shouldnt do anything with it, ask Jira Api if its a subtask(sub-anything, dont rely on the name)
                return issue
            }

            //End of Jises-109
            receiveEpicName(replica, issue, nodeHelper)

            receiveEpicLinkBeforeCreate(replica, nodeHelper, issue)
            if (firstSync) {
                return CreateIssue.create(
                        true,
                        replica,
                        issue,
                        connection,
                        issueBeforeScript,
                        traces,
                        blobMetadataList,
                        httpClient,
                        syncRequest) {

                    receiveEpicLinks(replica, issue, nodeHelper)
                    return null
                }
            } else {
                receiveEpicLinks(replica, issue, nodeHelper)
            }
            issue

        }


        private def log = org.slf4j.LoggerFactory.getLogger("com.exalate.scripts.Epic")

        def relationLevelError2 = { String msg, Throwable cause ->
            new IllegalStateException(msg, cause)
        }
        def relationLevelError = { String msg ->
            new IllegalStateException(msg)
        }
        def toRestIssueKey = { exIssueKey ->
            [
                    "id" : exIssueKey?.id,
                    "key": exIssueKey?.getURN(),
            ] as Map<String, Object>;
        }

        private BasicHubIssue sendEpicInAnyOrder(Long pollForEpicLinkUpdatesInterval, BasicHubIssue replica, BasicHubIssue issue, IConnection connection) {
            def issueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)
            def issueLevelError = { String msg ->
                new com.exalate.api.exception.IssueTrackerException(msg)
            }
            def issueLevelError2 = { String msg, Throwable e ->
                new com.exalate.api.exception.IssueTrackerException(msg, e)
            }
            final def gs = getGeneralSettings()

            final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
            final def epicLinkCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" }
            final def epicNameCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" }
            final def epicColourCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-color" }
            final def epicStatusCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-status" }
            final def epicThemeCfJson = fieldsJson.findAll { it.schema.custom == "com.atlassian.jira.plugin.system.customfieldtypes:labels" }.find { it.name == "Epic/Theme" }

            def search = searchFn(httpClient)

            def getIssueByIdOrKey = { idOrKey ->
                def response
                try {
                    //noinspection GroovyAssignabilityCheck
                    response = await(await(httpClient.authenticate(
                            none(),
                            httpClient
                                    .ws()
                                    .url(jiraCloudUrl+"/rest/api/2/issue/"+idOrKey)
                                    .withQueryString(seq(
                                            pair("fields", "id,key")
                                    ))
                                    .withMethod("GET"),
                            gs
                    )).get())
                } catch (Exception e) {
                    throw issueLevelError2("Unable to get the issue "+ idOrKey +", please contact Exalate Support: " + e.message, e)
                }
                if (response.status() != 200) {
                    throw issueLevelError("Can not get the issue "+ idOrKey +" (status "+ response.status() +"), please contact Exalate Support: "+ response.body())
                }
                def resultStr = response.body() as String
                def s = new groovy.json.JsonSlurper()
                def resultJson
                try {
                    resultJson = s.parseText(resultStr)
                } catch (Exception e) {
                    throw issueLevelError2("Can not parse the issue "+ idOrKey +" json, please contact Exalate Support: " + resultStr, e)
                }

/*
    {
      "expand": "names,schema",
      "startAt": 0,
      "maxResults": 50,
      "total": 1,
      "issues": [
        {
          "expand": "",
          "id": "10001",
          "self": "http://www.example.com/jira/rest/agile/1.0/board/92/issue/10001",
          "key": "HSP-1",
          "fields": {}
        }
      ]
    }
*/
                if (!(resultJson instanceof Map)) {
                    throw issueLevelError("Issue "+idOrKey+" json has unrecognized structure, please contact Exalate Support: " + resultStr)
                }
                resultJson as Map<String, Object>
            }

            def toRestIssueKeyInternal = { exIssueKey ->
                [
                        "id" : exIssueKey?.id,
                        "key": exIssueKey?.getURN(),
                ]
            }

            def toEpicContext = { epicIssueKey, storyIssueKeys ->
                [
                        "epic"   : toRestIssueKeyInternal(epicIssueKey),
                        "stories": storyIssueKeys
                ]
            }

            def getStories = { com.exalate.basic.domain.BasicIssueKey epicExIssueKey ->
                def epicLinkSearchClauseNames = epicLinkCfJson.clauseNames as List<String>
                final def epicLinkSearchClauseName = epicLinkSearchClauseNames[0]
                def jql = epicLinkSearchClauseName + " = " + epicExIssueKey.URN

                search(jql)
            }


            if (epicLinkCfJson != null && issue.customFields[epicLinkCfJson?.schema?.customId as String].value != null) {
                def epicKey = issue.customFields[epicLinkCfJson?.schema?.customId as String].value
                def epic = getIssueByIdOrKey(epicKey)

                def epicLinkId = epic.id as Long
                replica.customKeys."Epic Link" = ["id": epicLinkId, "key": epicKey]
                def exEpicIssueKey = new com.exalate.basic.domain.BasicIssueKey(epicLinkId, epicKey)
                def stories = getStories(exEpicIssueKey)
                replica.customKeys."epicContext" = toEpicContext(exEpicIssueKey, stories)
            }

            if (epicNameCfJson != null && issue.customFields[epicNameCfJson?.schema?.customId as String]?.value != null) {
                def stories = getStories(issueKey)
                replica.customKeys."Epic Name" = issue.customFields[epicNameCfJson?.schema?.customId as String].value
                if(epicThemeCfJson != null) {
                    replica.customFields."Epic/Theme" = issue.customFields[epicThemeCfJson?.schema?.customId as String]
                }
                if (issue.customFields[epicColourCfJson?.schema?.customId as String]?.value != null) {
                    replica.customFields."Epic Colour" = issue.customFields[epicColourCfJson?.schema?.customId as String]
                }
                if (issue.customFields[epicStatusCfJson?.schema?.customId as String]?.value != null) {
                    replica.customFields."Epic Status" = issue.customFields[epicStatusCfJson?.schema?.customId as String]
                }
                replica.customKeys."epicContext" = toEpicContext(issueKey, stories)
            }

            replica
        }
        private BasicHubIssue sendEpicHavingEpicFirst(Long pollForEpicLinkUpdatesInterval, BasicHubIssue replica, BasicHubIssue issue, IConnection connection) {
            def issueKey = new com.exalate.basic.domain.BasicIssueKey(issue.id as Long, issue.key)
            def issueLevelError = { String msg ->
                new com.exalate.api.exception.IssueTrackerException(msg)
            }
            def issueLevelError2 = { String msg, Throwable e ->
                new com.exalate.api.exception.IssueTrackerException(msg, e)
            }
            final def gs = getGeneralSettings()

            final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
            final def epicLinkCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" }
            final def epicNameCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" }
            final def epicColourCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-color" }
            final def epicStatusCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-status" }
            final def epicThemeCfJson = fieldsJson.findAll { it.schema.custom == "com.atlassian.jira.plugin.system.customfieldtypes:labels" }.find { it.name == "Epic/Theme" }

            def search = searchFn(httpClient)

            def getIssueByIdOrKey = { idOrKey ->
                def response
                try {
                    //noinspection GroovyAssignabilityCheck
                    response = await(await(httpClient.authenticate(
                            none(),
                            httpClient
                                    .ws()
                                    .url(jiraCloudUrl+"/rest/api/2/issue/"+idOrKey)
                                    .withQueryString(seq(
                                            pair("fields", "id,key")
                                    ))
                                    .withMethod("GET"),
                            gs
                    )).get())
                } catch (Exception e) {
                    throw issueLevelError2("Unable to get the issue "+ idOrKey +", please contact Exalate Support: " + e.message, e)
                }
                if (response.status() != 200) {
                    throw issueLevelError("Can not get the issue "+ idOrKey +" (status "+ response.status() +"), please contact Exalate Support: "+ response.body())
                }
                def resultStr = response.body() as String
                def s = new groovy.json.JsonSlurper()
                def resultJson
                try {
                    resultJson = s.parseText(resultStr)
                } catch (Exception e) {
                    throw issueLevelError2("Can not parse the issue "+ idOrKey +" json, please contact Exalate Support: " + resultStr, e)
                }

/*
    {
      "expand": "names,schema",
      "startAt": 0,
      "maxResults": 50,
      "total": 1,
      "issues": [
        {
          "expand": "",
          "id": "10001",
          "self": "http://www.example.com/jira/rest/agile/1.0/board/92/issue/10001",
          "key": "HSP-1",
          "fields": {}
        }
      ]
    }
*/
                if (!(resultJson instanceof Map)) {
                    throw issueLevelError("Issue "+idOrKey+" json has unrecognized structure, please contact Exalate Support: " + resultStr)
                }
                resultJson as Map<String, Object>
            }

            def toRestIssueKeyInternal = { exIssueKey ->
                [
                        "id" : exIssueKey?.id,
                        "key": exIssueKey?.getURN(),
                ]
            }

            def toEpicContext = { epicIssueKey, storyIssueKeys ->
                [
                        "epic"   : toRestIssueKeyInternal(epicIssueKey),
                        "stories": storyIssueKeys
                ]
            }

            def getStories = { com.exalate.basic.domain.BasicIssueKey epicExIssueKey ->
                def epicLinkSearchClauseNames = epicLinkCfJson.clauseNames as List<String>
                final def epicLinkSearchClauseName = epicLinkSearchClauseNames[0]
                def jql = epicLinkSearchClauseName + " = " + epicExIssueKey.URN

                search(jql)
            }

            def getEpicIdOrKey = { BasicHubIssue childIssue ->
                issue.parentId ?: issue.customFields[epicLinkCfJson?.schema?.customId as String].value
            }


            if (epicLinkCfJson != null && issue.customFields[epicLinkCfJson?.schema?.customId as String].value != null) {
                def epicIdOrKey = getEpicIdOrKey(issue)
                def epic = getIssueByIdOrKey(epicIdOrKey)
                def epicLinkId = epic.id as Long
                def epicKey = epic.key
                replica.customKeys."Epic Link" = ["id": epicLinkId, "key": epicKey]
            }

            if (epicNameCfJson != null && issue.customFields[epicNameCfJson?.schema?.customId as String]?.value != null) {
                // epic
                def stories = getStories(issueKey)
                replica.customKeys."Epic Name" = issue.customFields[epicNameCfJson?.schema?.customId as String].value
                if(epicThemeCfJson != null) {
                    replica.customFields."Epic/Theme" = issue.customFields[epicThemeCfJson?.schema?.customId as String]
                }
                if (issue.customFields[epicColourCfJson?.schema?.customId as String]?.value != null) {
                    replica.customFields."Epic Colour" = issue.customFields[epicColourCfJson?.schema?.customId as String]
                }
                if (issue.customFields[epicStatusCfJson?.schema?.customId as String]?.value != null) {
                    replica.customFields."Epic Status" = issue.customFields[epicStatusCfJson?.schema?.customId as String]
                }
                replica.customKeys."epicContext" = toEpicContext(issueKey, stories)
            }

            replica
        }

        BasicHubIssue receiveEpicName(BasicHubIssue replica, BasicHubIssue issue, nodeHelper) {
            def issueLevelError = { String msg ->
                new com.exalate.api.exception.IssueTrackerException(msg)
            }

            final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
            final def epicNameCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" }
            final def epicColourCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-color" }
            final def epicStatusCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-status" }
            final def epicThemeCfJson = fieldsJson.findAll { it.schema.custom == "com.atlassian.jira.plugin.system.customfieldtypes:labels" }.find { it.name == "Epic/Theme" }

            if (replica.customKeys."Epic Name" != null) {
                def cf = issue.customFields[epicNameCfJson.schema.customId as String]
                if (cf != null) {
                    cf.value = replica.customKeys."Epic Name"
                    if (replica.customFields."Epic/Theme"?.value && epicThemeCfJson){
                        def epicThemeCf = issue.customFields[epicThemeCfJson.schema.customId as String]
                        epicThemeCf?.value = replica.customFields."Epic/Theme"?.value
                    }
                }
            }
            if (replica.customFields."Epic Colour"?.value) {
                def project = nodeHelper.getProject(issue.projectKey)
                def isInScreen = getFieldScreens(log, httpClient, "customfield_" + epicColourCfJson.schema.customId).findAll{it.id == project.id}

                def cf = issue.customFields[epicColourCfJson.schema.customId as String]
                if (cf != null && isInScreen) {
                    cf.value = replica.customFields."Epic Colour".value
                }
            }
            if (replica.customFields."Epic Status"?.value) {
                def cf = issue.customFields[epicStatusCfJson.schema.customId as String]
                if (cf != null) {
                    def projectKey = issue.projectKey ?: issue.project?.key
                    def project = nodeHelper.getProject(projectKey)
                    def issueTypeName = issue.typeName ?: issue.type?.name
                    def issueType = nodeHelper.getIssueType(issueTypeName, project?.key)
                    if (!project) {
                        throw new IssueTrackerException("Project with key `${projectKey}` does not exist!".toString())
                    }
                    if (!issueType) {
                        throw new IssueTrackerException("Issue type `${issueTypeName}` is not valid for project `${projectKey}`".toString())
                    }
                    def option = nodeHelper.getOption(issue, epicStatusCfJson.name as String, replica.customFields."Epic Status"?.value?.value as String)
                    if (option) {
                        cf.value = [["id":option.id as String]]
                    }
                }
            }
            issue
        }

        /*
          set the epic link on the issue
        */
        private BasicHubIssue receiveEpicLinkBeforeCreate(com.exalate.basic.domain.hubobject.v1.BasicHubIssue replica, nodeHelper, com.exalate.basic.domain.hubobject.v1.BasicHubIssue issue) {
            if (replica.customKeys."Epic Link"?.id != null) {
                //replica.customKeys."Epic Link" = ["id": epicLink.id, "key": epicLink.key]
                def remoteEpicId = replica.customKeys."Epic Link".id as Long
                log.debug("Getting local issue by remote id " + remoteEpicId)
                def localHubEpicKey = ExalateApi.getLocalIssueFromRemoteId(remoteEpicId, nodeHelper)
                if (localHubEpicKey != null) {
                    log.info("SETTING EPIC for issue (being created for remote issue `${replica.key}` ${replica.id}) to `${localHubEpicKey.URN}` (${localHubEpicKey.idStr})")
                    issue.parentId = localHubEpicKey.idStr
                } else {
                    log.debug("Could not find Epic for issue (being created for remote issue `${replica.key}` ${replica.id}) by remote epic info: `${replica.customKeys."Epic Link".key}` ${replica.customKeys."Epic Link".id}")
                }
            }

            issue
        }

        BasicHubIssue receiveEpicLinks(BasicHubIssue replica, BasicHubIssue issue, nodeHelper) {
            if (replica
                    .customKeys
                    ."epicContext" == null) {
                return issue
            }

            log.debug("Received epic context: " + replica.customKeys."epicContext")
            def issueLevelError = { String msg ->
                new com.exalate.api.exception.IssueTrackerException(msg)
            }
            def issueLevelError2 = { String msg, Throwable e ->
                new com.exalate.api.exception.IssueTrackerException(msg, e)
            }
            final def gs = getGeneralSettings()

            final def fieldsJson = getFieldsJson(httpClient).findAll { it.schema instanceof Map }
            final def epicLinkCfJson = fieldsJson.find { it.schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" }

            def updateIssue = { com.exalate.basic.domain.BasicIssueKey issueKeyToUpdate, Map<String, Object> json ->
                //  PUT /rest/api/2/issue/{issueIdOrKey}
                /*
                {
                  "fields": {
                    "summary": "This is a shorthand for a set operation on the summary field",
                    "customfield_10010": 1,
                    "customfield_10000": "This is a shorthand for a set operation on a text custom field"
                  }
                }
                 */
                def jsonStr = groovy.json.JsonOutput.toJson(json)
                def response
                try {
                    //noinspection GroovyAssignabilityCheck
                    httpClient.put("/rest/api/2/issue/"+issueKeyToUpdate.id, jsonStr, ["overrideScreenSecurity": "true"])
                } catch (Exception e) {
                    throw issueLevelError2("Unable to update issue `"+ issueKeyToUpdate.URN +"`, please contact Exalate Support:  \n" +
                            "PUT "+jiraCloudUrl+"/rest/api/2/issue/"+issueKeyToUpdate.id+"\nBody: "+jsonStr+"\nError Message:"+ e.message, e)
                }
            }


//def jProject = pm.getProjectObj(project.id)

//def proxyAppUser = nserv.getProxyUser()
            def getLocalIssueKey = { remoteSuspectId, currentIssue ->
                if(replica.id.equals((remoteSuspectId as Long) as String)) {
                    return new com.exalate.basic.domain.BasicIssueKey(currentIssue.id as Long, currentIssue.key)
                }
                nodeHelper.getLocalIssueKeyFromRemoteId(remoteSuspectId as Long)
            }


            def getLocalIssue = { remoteSuspectId, currentIssue ->
                if(replica.id.equals((remoteSuspectId as Long) as String)) {
                    return currentIssue
                }
                nodeHelper.getLocalIssueFromRemoteId(remoteSuspectId as Long)
            }


            def getLocalEpicIssue = { currentIssue ->
                def epicContext = replica.customKeys."epicContext"
                if (!(epicContext instanceof Map)) {
                    return null
                }
                def remoteEpicId = epicContext.epic.id
                getLocalIssue(remoteEpicId, currentIssue)
            }

            if (issue.id == null) {
                throw new com.exalate.api.exception.IssueTrackerException(""" It seems, that the issue has not been created yet. Please change your create processor to create the issue and populate the `issue.id` property before using the `AgileSync.receiveEpicAfterCreation(...)` """.toString())
            }
            // try to link all the stories to the epic:
            def localEpicJissue = getLocalEpicIssue(issue)
            if (localEpicJissue != null) {
                replica
                        .customKeys
                        ."epicContext"
                        .stories
                        .collect { story -> getLocalIssueKey(story.id, issue) }
                        .findAll { it != null }
                        .each { localStory ->
//            log.debug("linking the localStory `"+localStory.key+"` to epic `"+ localEpicJissue.key +"` for the issue `"+ jIssue.key +"` for remote issue `"+ replica.key +"`")
                            updateIssue(
                                    new com.exalate.basic.domain.BasicIssueKey(localStory.id as Long, localStory.urn),
                                    [
                                            "fields" : [
                                                    "parent": ["id": localEpicJissue.id]
                                            ]
                                    ]
                            )
                        }
            }
            def epicLinkCfIdStr = epicLinkCfJson?.schema?.customId as String
            def epicLinkCfValueInternal = issue.customFields[epicLinkCfIdStr].value
            issue.customFields.remove(epicLinkCfIdStr)
            if (replica.customKeys."epicContext" == null &&
                    epicLinkCfValueInternal != null) {
                issue.customFields[epicLinkCfIdStr].value = null
            }

            issue
        }

    }

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //~EXALATE API EXTERNAL SCRIPT~
    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    static class ExalateApi {
        static log = org.slf4j.LoggerFactory.getLogger("com.exalate.scripts.Epic")

        static IIssueKey getLocalIssueFromRemoteId(remoteIssueId, nodeHelper) {
            if (remoteIssueId == null) {
                return null
            }
            if (remoteIssueId instanceof Long) {
                nodeHelper.getLocalIssueKeyFromRemoteId(remoteIssueId.toString())
            } else {
                nodeHelper.getLocalIssueKeyFromRemoteId(remoteIssueId as String)
            }
        }

    }
}