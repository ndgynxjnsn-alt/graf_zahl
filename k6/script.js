import http from "k6/http";
import { sleep, check } from "k6";

// Drive both environments so each has continuous telemetry.
const TARGETS = [
  __ENV.PROD_URL || "http://app-production:8080",
  __ENV.TEST_URL || "http://app-test:8080",
];

const ENDPOINTS = ["/rolldice", "/work", "/flaky", "/"];

export const options = {
  // A gentle, steady trickle is enough to keep dashboards populated.
  scenarios: {
    steady: {
      executor: "constant-vus",
      vus: 4,
      duration: __ENV.DURATION || "48h",
    },
  },
  thresholds: {},
};

export default function () {
  const base = TARGETS[Math.floor(Math.random() * TARGETS.length)];
  const path = ENDPOINTS[Math.floor(Math.random() * ENDPOINTS.length)];
  const res = http.get(`${base}${path}`);
  check(res, { "status is 2xx or 5xx": (r) => r.status > 0 });
  sleep(Math.random() * 1.5 + 0.5);
}
