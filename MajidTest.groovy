public class MajidTest{
  public def mySync(){
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
