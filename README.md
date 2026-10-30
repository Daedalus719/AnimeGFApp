# AnimeGF

A lightweight Android WebView application that provides native app access to https://anime.gf

## About

This is a simple WebView wrapper that displays the anime.gf website as a native Android application. 

## Changes
> v1.0 Initial release

> v1.1 -  Added floating refresh button (double tap to refresh)

> v1.2 - Added Context Menu that opens when holding on image (save image, copy image url and search image with google lens) and on any url (open in browser, copy url)

> v1.3 - Added launch splash screen (icon with a circular loading ring driven by real page-load progress). The floating refresh button is now a collapsible round menu (drag it anywhere, tap to expand) with: Reload, Snippets (a modal to add / edit / copy / delete saved text, kept between restarts) and Desktop mode ("Desktop identity" keeps the mobile layout but identifies as a desktop browser `experimental not working well`; "Desktop view" renders the real wide layout at 800 / 1024 / 1280 px with steady pinch-zoom)

> v1.4 - Replaced the system text-selection toolbar (it often closed while dragging the selection) with the app's own glass toolbar: Cut, Copy, Paste, Select all, Search and Translate. The image / link long-press menu is rebuilt in the same glass style and is now only opened for real images and links - buttons, modal triggers, `#` and `javascript:` links no longer trigger it. Saving an image now goes through the gallery (Pictures/AnimeGF) and works on Android 10+. Search and Translate from the selection toolbar open in an in-app glass browser modal (with a button to continue in the external browser).


## Legal Notice

**Important**: The website content displayed within this application (anime.gf) belongs solely to its rightful owner. This application is **only a WebView wrapper** that allows the website to be used as a native Android app. 

This project does not host, modify, or claim ownership of any content displayed. It is merely a presentation layer for the original website.

## Requirements

- Android 5.0 (API 21) or higher
- Internet connection for website access


## License

This WebView wrapper is provided as-is. Respect the terms and conditions of the original website at https://anime.gf
