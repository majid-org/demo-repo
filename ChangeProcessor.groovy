import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.request.ISyncRequest
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import com.exalate.hubobject.jira.AttachmentHelper
import com.exalate.hubobject.jira.CommentHelper
import scala.collection.Seq
import services.jcloud.hubobjects.NodeHelper
import services.replication.PreparedHttpClient

/**
Usage:

ChangeProcessor.execute(
  replica,
  issue,
  previous,
  issueBeforeScript,
  issueKey,
  connection,
  traces,
  blobMetadataList,
  nodeHelper,
  commentHelper,
  attachmentHelper,
  workLogHelper,
  workflowHelper,
  serviceDeskHelper,
  log,
  httpClient,
  syncRequest
)
 * */
class ChangeProcessor {
    static execute(
            BasicHubIssue replica,
            BasicHubIssue issue,
            BasicHubIssue previous,
            BasicHubIssue issueBeforeScript,
            com.exalate.basic.domain.BasicIssueKey issueKey,
            IConnection connection,
            List<com.exalate.api.domain.twintrace.INonPersistentTrace> traces,
            Seq<com.exalate.api.domain.IBlobMetadata> blobMetadataList,
            NodeHelper nodeHelper,
            CommentHelper commentHelper,
            AttachmentHelper attachmentHelper,
            com.exalate.hubobject.jira.WorkLogHelper workLogHelper,
            com.exalate.hubobject.jira.WorkflowHelper workflowHelper,
            services.jcloud.hubobjects.ServiceDeskHelper serviceDeskHelper,
            log, //ch.qos.logback.classic.Logger log
            PreparedHttpClient httpClient,
            ISyncRequest syncRequest) {
        log.info("Debugging. Remote type: " + replica.type?.name)
        log.info("Debugging. Remote project key: " + replica.project?.key)
        log.info("Debugging. Local comments: " + issue.comments)
        log.info("Debugging. Remote type (from previous): " + previous.type?.name)

        if (replica.type?.name != previous.type?.name){
            issue.comments = commentHelper.addComment(
                    "This issue was moved on itrack.barco.com from issuetype ${previous.type?.name} to ${replica.type?.name}. Please move the issue manually to the new issuetype",
                    issue.comments)
        }
        if (replica.project?.name != previous.project?.name){
            issue.comments = commentHelper.addComment(
                    "This issue was moved on itrack.barco.com from project ${previous.project?.name} to ${replica.project?.name}. Please move the issue manually to the new project",
                    issue.comments)
        }
        issue.summary = replica.summary
        issue.description = replica.description
        issue.environment = replica.environment
        issue.securityLevel = replica.securityLevel

//        def desiredIssueType = replica.type?.name
//        issue.type = nodeHelper.getIssueType(desiredIssueType, issue.project)
//        issue.typeName     = issue.type?.name
//        if (issue.type == null) {
//            throw new com.exalate.api.exception.IssueTrackerException("""
//Can not find issue type `${desiredIssueType}` for the project `${issue.project?.key}`.
//Please check project settings or change the script""".toString()
//            )
//        }
        def desiredPriority = replica.priority.name
        def localPrio = nodeHelper.getPriority(desiredPriority)
        if (localPrio == null) {
//            throw new com.exalate.api.exception.IssueTrackerException("""
//Can not find priority `${desiredPriority}` for the project `${issue.project?.key}`.
//Please check project settings or change the script""".toString()
//            )
        } else {
            issue.priority = localPrio
        }

        issue.labels = replica.labels

        issue.due = replica.due

        if (replica.assignee) {
            issue.assignee = nodeHelper.getUserByEmail(replica.assignee?.email) ?: ({
                def defaultAssignee = new com.exalate.basic.domain.hubobject.v1.BasicHubUser()
                defaultAssignee.key = "-1"
                defaultAssignee
            })()
        } else {
            issue.assignee = null
        }

        VersionSync.receive(replica, issue, connection, nodeHelper, httpClient)
        ComponentSync.receive(replica, issue, connection, nodeHelper, httpClient)

        def js = new groovy.json.JsonSlurper()
        def fieldsJsonStr = new JiraClient(httpClient).http(
                "GET",
                "/rest/api/2/issue/${issue.key}/editmeta".toString(),
                null,
                null,
                ["Accept": ["application/json"]]
        )
        def fieldsJson = js.parseText(fieldsJsonStr)
        def getFieldJson = { String fieldName ->
            fieldsJson."fields".values().find { Map<String, Object> v -> v."name" == fieldName } as Map<String, Object>
        }
        def canBeSet = { String fieldName ->
            def fieldJson = getFieldJson(fieldName)
            fieldJson != null
        }
        def getExOption = { String fieldName, String optValue ->
            def fieldJson = getFieldJson(fieldName)
            fieldJson?."allowedValues"?.findAll { Map<String, Object> v ->
                v."value" == optValue
            }?.collect { Map<String, Object> v ->
                def exOption = new com.exalate.basic.domain.hubobject.v1.BasicHubOption()
                exOption.id = v."id" as Long
                exOption.value = v."value" as String
                exOption
            }?.find()
        }
        if (replica.customFields."Business Value"?.value && canBeSet("Business Value")) issue.customFields."Business Value"?.value = replica.customFields."Business Value"?.value
        if (replica.customFields."Flagged"?.value && canBeSet("Flagged")) issue.customFields."Flagged"?.value = replica.customFields."Flagged".value.collect { com.exalate.basic.domain.hubobject.v1.BasicHubOption v ->
            getExOption("Flagged", v.value)
        }?.findAll()
        if (replica.customFields."Severity"?.value && canBeSet("Severity")) issue.customFields."Severity"?.value = getExOption("Severity", replica.customFields."Severity"?.value?.value as String)
        if (replica.customFields."Start date"?.value && canBeSet("Start date")) issue.customFields."Start date"?.value = replica.customFields."Start date"?.value

        if (replica.customFields."Observed By"?.value && canBeSet("Observed By")) issue.customFields."Observed By"?.value = getExOption("Observed By", replica.customFields."Observed By"?.value?.value as String)
        if (replica.customFields."Observed During"?.value && canBeSet("Observed During")) issue.customFields."Observed During"?.value = getExOption("Observed During", replica.customFields."Observed During"?.value?.value as String)
        if (replica.customFields."Project Type"?.value && canBeSet("Project Type")) issue.customFields."Project Type"?.value = getExOption("Project Type", replica.customFields."Project Type"?.value?.value as String)

        AgileSync.receiveEpicBeforeCreation(replica, issue, nodeHelper, httpClient)

        AgileSync.receiveEpicAfterCreation(replica, issue, nodeHelper, httpClient)
        AgileSync.receiveRank(replica, issue, nodeHelper, httpClient)
        AgileSync.receiveSprints(AgileSync.skipOnNoBoardFound(), replica, issue, nodeHelper, httpClient)

        issue.attachments = attachmentHelper.mergeAttachments(issue, replica)
        issue.comments = commentHelper.mergeComments(issue, replica)

        IssueLinkSync.receive(replica, issue, httpClient, nodeHelper)

        // RESOLUTION
        if (replica.resolution == null && issue.resolution != null) {
            // if the remote issue is not resolved, but the local issue is

            issue.resolution = null
        }
        if (replica.resolution != null && issue.resolution == null) {
            // the remote issue is resolved, but the local isn't - look up the correct resolution object.

            // use 'done' as resolution if the remote resolution is not found
            issue.resolution = nodeHelper.getResolution(replica.resolution.name) ?: nodeHelper.getResolution("Done")
        }

        if (replica.project.name == issue.project.name && replica.type.name == issue.type.name) {
            def desiredStatusName = ([:].find { k,_ -> k.equalsIgnoreCase(replica.status?.name) }?.value)
            if (true && desiredStatusName == null) {
                desiredStatusName = replica.status?.name
            }
            StatusSync.receive(
                    true,
                    [:],
                    [
                            "ROG":12406,
                            "QE":12601,
                    ],
                    [
                            12406:"""{"name":"Defect_SCRUM_wfs01","id":12406,"shared":[{"id":11900,"key":"ROG","name":"Roger"},{"id":12303,"key":"CTO0009","name":"Template Combined Defect & Scrum"},{"id":12302,"key":"CTO0022","name":"Template Defect & Scrum + Risk"},{"id":12308,"key":"VAL0001","name":"ValidationScrum"}],"mappings":[{"name":"TASK_w","displayName":"TASK_w","scope":"GLOBAL","issueTypes":["10104","3","5"],"jiraDefault":false,"system":false,"default":true},{"name":"Barco QA workflow","displayName":"Barco QA workflow","scope":"GLOBAL","issueTypes":["10203"],"jiraDefault":false,"system":false,"default":false},{"name":"DEFECT_w_restored","displayName":"DEFECT_w_restored","scope":"GLOBAL","issueTypes":["10103","10000","10202","10101","10001","10201"],"jiraDefault":false,"system":false,"default":false}],"issueTypes":[{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10303&avatarType=issuetype","name":"Defect","id":"10103","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10307&avatarType=issuetype","name":"Epic","description":"Created by Jira Software - do not edit or delete. Issue type for a big user story that needs to be broken down.","id":"10000","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=14857&avatarType=issuetype","name":"Implementation Task","description":"Use this task to work with greenhopper","id":"10202","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=14858&avatarType=issuetype","name":"QA Stop","id":"10203","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10310&avatarType=issuetype","name":"Request","id":"10104","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10308&avatarType=issuetype","name":"Risk","id":"10101","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10315&avatarType=issuetype","name":"Story","description":"Created by Jira Software - do not edit or delete. Issue type for a user story.","id":"10001","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10318&avatarType=issuetype","name":"Task","description":"A task that needs to be done.","id":"3","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=13698&avatarType=issuetype","name":"Implementation Sub-Task","description":"Task used in Greenhopper or Task for planning purposes","id":"10201","subTask":true,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10316&avatarType=issuetype","name":"Sub-Task","description":"The sub-task of the issue","id":"5","subTask":true,"defaultIssueType":false}],"admin":true,"sysAdmin":false,"defaultScheme":false,"totalWorkflows":53,"draftScheme":false,"currentUser":"sergey","parentId":12406}""",
                            12601:"""{"name":"Defect_SCRUM_wfs01","id":12601,"shared":[{"id":12500,"key":"AR","name":"ArturTest"},{"id":12504,"key":"EE","name":"EVL-LUMENS"},{"id":12505,"key":"QE","name":"QAWeb Enterprise"},{"id":12503,"key":"RAE","name":"REX ARTUS EXT"},{"id":11900,"key":"ROG","name":"Roger"},{"id":12307,"key":"CTO0009","name":"Template Combined Defect & Scrum"},{"id":12400,"key":"CTO0022","name":"Template Defect & Scrum + Risk"}],"mappings":[{"name":"TASK_w","displayName":"TASK_w","scope":"GLOBAL","issueTypes":["10104","3","5"],"jiraDefault":false,"system":false,"default":true},{"name":"Barco QA workflow","displayName":"Barco QA workflow","scope":"GLOBAL","issueTypes":["10203"],"jiraDefault":false,"system":false,"default":false},{"name":"DEFECT_w2","displayName":"DEFECT_w2","scope":"GLOBAL","issueTypes":["10103","10000","10202","10101","10001","10201"],"jiraDefault":false,"system":false,"default":false}],"issueTypes":[{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10303&avatarType=issuetype","name":"Defect","id":"10103","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10307&avatarType=issuetype","name":"Epic","description":"Created by Jira Software - do not edit or delete. Issue type for a big user story that needs to be broken down.","id":"10000","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=14857&avatarType=issuetype","name":"Implementation Task","description":"Use this task to work with greenhopper","id":"10202","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=14858&avatarType=issuetype","name":"QA Stop","id":"10203","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10310&avatarType=issuetype","name":"Request","id":"10104","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10308&avatarType=issuetype","name":"Risk","id":"10101","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10315&avatarType=issuetype","name":"Story","description":"Created by Jira Software - do not edit or delete. Issue type for a user story.","id":"10001","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10318&avatarType=issuetype","name":"Task","description":"A task that needs to be done.","id":"3","subTask":false,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=13698&avatarType=issuetype","name":"Implementation Sub-Task","description":"Task used in Greenhopper or Task for planning purposes","id":"10201","subTask":true,"defaultIssueType":false},{"iconUrl":"/secure/viewavatar?size=xsmall&avatarId=10316&avatarType=issuetype","name":"Sub-task","description":"The sub-task of the issue","id":"5","subTask":true,"defaultIssueType":false}],"admin":true,"sysAdmin":false,"defaultScheme":false,"totalWorkflows":52,"draftScheme":false,"currentUser":"sergey","parentId":12601}""",
                    ],
                    [
                            "DEFECT_w_restored":"""{"name":"DEFECT_w_restored","description":"","sources":[{"fromStatus":{"statusCategory":{"aliases":["To Do"],"translatedName":"To Do","sequence":2,"colorName":"blue-gray","primaryAlias":"To Do","name":"New","key":"new","id":2},"description":"The issue is reported and ready for the assignee to start work on it.","iconUrl":"/images/icons/statuses/open.png","name":"Reported","id":"10604"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10602"},"transitionName":"Investigated"}]},{"fromStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10602"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"Someone is actively working on this issue.","iconUrl":"/images/icons/statuses/inprogress.png","name":"In Progress","id":"3"},"transitionName":"In progress"},{"toStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"transitionName":"Resolved","screen":{"id":12315,"name":"DEFECT_resolve_trans_s"}}]},{"fromStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"Someone is actively working on this issue.","iconUrl":"/images/icons/statuses/inprogress.png","name":"In Progress","id":"3"},"targets":[{"toStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"transitionName":"Resolved","screen":{"id":12315,"name":"DEFECT_resolve_trans_s"}},{"toStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10602"},"transitionName":"Investigated"}]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"translatedName":"In Progress","sequence":3,"colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10602"},"transitionName":"Investigated"},{"toStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"","iconUrl":"/images/icons/status_verified.gif","name":"Verified","id":"10400"},"transitionName":"Verified","screen":{"id":12315,"name":"DEFECT_resolve_trans_s"}}]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"","iconUrl":"/images/icons/status_verified.gif","name":"Verified","id":"10400"},"targets":[]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"translatedName":"Done","sequence":4,"colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"The issue is considered finished, the resolution is correct. Issues which are closed can be reopened.","iconUrl":"/images/icons/statuses/closed.png","name":"Closed","id":"6"},"targets":[]}],"id":12613,"displayName":"DEFECT_w_restored","admin":true}""",
                            "DEFECT_w2":"""{"name":"DEFECT_w2","description":"","sources":[{"fromStatus":{"statusCategory":{"aliases":["To Do"],"sequence":2,"translatedName":"To Do","colorName":"blue-gray","primaryAlias":"To Do","name":"New","key":"new","id":2},"description":"The issue is reported and ready for the assignee to start work on it.","iconUrl":"/images/icons/statuses/open.png","name":"Reported","id":"10601"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10600"},"transitionName":"Investigated"}]},{"fromStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10600"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"Someone is actively working on this issue.","iconUrl":"/images/icons/statuses/inprogress.png","name":"In Progress","id":"3"},"transitionName":"In Progress"},{"toStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"transitionName":"Resolved","screen":{"id":12302,"name":"DEFECT_resolve_trans_s"}}]},{"fromStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"Someone is actively working on this issue.","iconUrl":"/images/icons/statuses/inprogress.png","name":"In Progress","id":"3"},"targets":[{"toStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10600"},"transitionName":"Investigated"},{"toStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"transitionName":"Resolved","screen":{"id":12302,"name":"DEFECT_resolve_trans_s"}}]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"A resolution has been taken, and it is awaiting verification by reporter. From here issues are either reopened, or are closed.","iconUrl":"/images/icons/statuses/resolved.png","name":"Resolved","id":"5"},"targets":[{"toStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"","iconUrl":"/images/icons/status_verified.gif","name":"Verified","id":"10400"},"transitionName":"Verified","screen":{"id":12302,"name":"DEFECT_resolve_trans_s"}},{"toStatus":{"statusCategory":{"aliases":[],"sequence":3,"translatedName":"In Progress","colorName":"yellow","primaryAlias":"In Progress","name":"In Progress","key":"indeterminate","id":4},"description":"","iconUrl":"/images/icons/statuses/generic.png","name":"Investigated","id":"10600"},"transitionName":"Investigated"}]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"","iconUrl":"/images/icons/status_verified.gif","name":"Verified","id":"10400"},"targets":[]},{"fromStatus":{"statusCategory":{"aliases":["Done"],"sequence":4,"translatedName":"Done","colorName":"green","primaryAlias":"Done","name":"Complete","key":"done","id":3},"description":"The issue is considered finished, the resolution is correct. Issues which are closed can be reopened.","iconUrl":"/images/icons/statuses/closed.png","name":"Closed","id":"6"},"targets":[]}],"id":12801,"displayName":"DEFECT_w2","admin":true}""",
                    ],
                    replica,
                    issue,
                    nodeHelper,
                    httpClient
            )
        }
    }
}
