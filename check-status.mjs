import { graphql as baseGraphql } from "@octokit/graphql";
import fs from "fs";
import fetch from "node-fetch";

// Required environment variables
const [owner, repo] = process.env.REPO.split("/");
const projectNumber = 3; // 🔁 Change this to your repo-level project number
const token = process.env.GH_TOKEN;

// GitHub GraphQL client
const graphql = baseGraphql.defaults({
  headers: {
    authorization: `token ${token}`,
  },
});

// GraphQL query for repo-level project
const query = `
  query($owner: String!, $repo: String!, $projectNumber: Int!) {
    repository(owner: $owner, name: $repo) {
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
                  field {
                    ... on ProjectV2SingleSelectField {
                      name
                    }
                  }
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

// Load previous statuses
const oldStatus = fs.existsSync(statusPath)
  ? JSON.parse(fs.readFileSync(statusPath, "utf-8"))
  : {};
const newStatus = {};
const changes = [];

// Fetch current status data
let result;
try {
  result = await graphql(query, { owner, repo, projectNumber });
} catch (err) {
  console.error("❌ GraphQL Error:", JSON.stringify(err, null, 2));
  process.exit(1);
}

const items = result.repository.projectV2.items.nodes;

for (const item of items) {
  const issue = item.content;
  if (!issue) continue;

  const statusField = item.fieldValues.nodes.find(
    (n) => n.field?.name === "Status"
  );
  if (!statusField) continue;

  const newVal = statusField.name;
  newStatus[issue.number] = newVal;

  if (oldStatus[issue.number] && oldStatus[issue.number] !== newVal) {
    changes.push({
      number: issue.number,
      old: oldStatus[issue.number],
      new: newVal,
    });
  }
}

// Save updated status data
fs.mkdirSync("status-tracking", { recursive: true });
fs.writeFileSync(statusPath, JSON.stringify(newStatus, null, 2));

// Add comments to issues with changed status
for (const change of changes) {
  const res = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/issues/${change.number}/comments`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        body: `🔄 Issue status changed from \`${change.old}\` to \`${change.new}\`.`,
      }),
    }
  );

  if (!res.ok) {
    const error = await res.json();
    console.error(`❌ Failed to comment on issue #${change.number}:`, error);
  } else {
    console.log(`✅ Commented on issue #${change.number}`);
  }
}
