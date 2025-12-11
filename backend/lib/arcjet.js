import arcjet, { tokenBucket, shield, detectBot } from "@arcjet/node";

import "dotenv/config";

// init arcjet
export const aj = arcjet({
    key: process.env.ARCJET_KEY,
    characteristics: ["ip.src"],
    rules: [
        // shield protects against bots and other threats such as sql injection, xss, etc.
        shield({mode: "DRY_RUN"}),
        detectBot({
            mode: "DRY_RUN",
            // block all the bots except search engine bots
            allow:[
                "CATEGORY:SEARCH_ENGINE",

            ]
        }),
        // rate limiting - DISABLED for development
        tokenBucket({
            mode: "DRY_RUN",
            refillRate: 1000, // Very high rate
            interval: 1,
            capacity: 1000 // Very high capacity
        })
    ],
});