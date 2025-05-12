import { graphql as baseGraphql } from "@octokit/graphql";
import fs from "fs";
import fetch from "node-fetch";

const graphql = baseGraphql.defaults({
  headers: {
    authorization: `token ${process.env.GH_TOKEN}`,
  },
});

const [owner, repo] = process.env.REPO.split("/");
const projectNumber = 1;

const query = `
  query($owner: String!, $repo: String!, $projectNumber: Int!) {
    organization(login: $owner) {
      projectV2(number: $projectNumber) {
        items(first: 100) {
          nodes {
            content {
              ... on Issue {
                number
                title
              }
            }
            fieldValues(first: 10) {
              nodes {
                ... on ProjectV2ItemFieldSingleSelectValue {
                  field { name }
                  name
                }
              }
            }
          }
        }
      }
    }
  }
`;

const statusPath = "status-tracking/issue-status.json";
const oldStatus = fs.existsSync(statusPath)
  ? JSON.parse(fs.readFileSync(statusPath, "utf-8"))
  : {};
const newStatus = {};
const changes = [];

const result = await graphql(query, {
  owner, // now refers to the org name
  projectNumber
});
const items = result.repository.projectV2.items.nodes;

for (const item of items) {
  const issue = item.content;
  if (!issue) continue;
  const statusField = item.fieldValues.nodes.find(n => n.field.name === "Status");
  if (!statusField) continue;

  const newVal = statusField.name;
  newStatus[issue.number] = newVal;

  if (oldStatus[issue.number] && oldStatus[issue.number] !== newVal) {
    changes.push({
      number: issue.number,
      old: oldStatus[issue.number],
      new: newVal
    });
  }
}

fs.mkdirSync("status-tracking", { recursive: true });
fs.writeFileSync(statusPath, JSON.stringify(newStatus, null, 2));

for (const change of changes) {
  const res = await fetch(`https://api.github.com/repos/${owner}/${repo}/issues/${change.number}/comments`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.GH_TOKEN}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      body: `🔄 Issue status changed from \`${change.old}\` to \`${change.new}\`.`
    })
  });
  console.log(`Commented on issue #${change.number}`);
}
