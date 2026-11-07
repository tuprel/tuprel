# Public documentation preview

Build the static documentation preview with Node.js 22 or newer.

```sh
node website/build.mjs
```

Open `website/build/index.html` or serve `website/build` from a local static
server. The build validates the paths to the linked repository guides and
reuses the existing light and dark workflow artwork. The preview has no
runtime JavaScript or hosting dependency.

The landing page describes features available in the Development Preview.
Installation and integration pages link to the maintained repository guides.
Public hosting, domain configuration and release downloads are separate gates.
