import com.exalate.api.domain.IIssueKey
import com.exalate.api.domain.connection.IConnection
import com.exalate.api.domain.hubobject.v1_2.IHubProject
import com.exalate.api.exception.IssueTrackerException
import com.exalate.basic.domain.hubobject.v1.BasicHubIssue
import services.jcloud.hubobjects.NodeHelper

public class MajidTest{
  public def mySync(BasicHubIssue issue, BasicHubIssue replica){
    replica.key            = issue.key
    replica.type           = issue.type
    replica.assignee       = issue.assignee
    replica.reporter       = issue.reporter
    replica.summary        = issue.summary
    replica.description    = issue.description
    replica.labels         = issue.labels
    replica.comments       = issue.comments
    replica.resolution     = issue.resolution
    replica.status         = issue.status
    replica.parentId       = issue.parentId
    replica.priority       = issue.priority
    replica.attachments    = issue.attachments
    replica.project        = issue.project
  }
}
