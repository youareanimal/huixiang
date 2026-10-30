# Translations

PocketTS speaks English, Finnish, German, Spanish, French, Simplified Chinese and Russian. It follows the phone's
language, or the one chosen in PocketTS's Settings › UI › Language. On Android 13 and later, that choice is the same
as the phone's own per-app setting: Settings › Apps › PocketTS › Language.

| Language | File | Checked by a native speaker |
|---|---|---|
| English | `app/src/main/res/values/strings.xml` | yes (the original) |
| Finnish | `values-fi/strings.xml` | yes |
| German | `values-de/strings.xml` | not yet |
| Spanish | `values-es/strings.xml` | not yet |
| French | `values-fr/strings.xml` | not yet |
| Simplified Chinese | `values-b+zh+Hans/strings.xml` | not yet |
| Russian | `values-ru/strings.xml` | not yet |

The translations were written with AI help, like the rest of PocketTS. If you speak one of these languages and
something reads oddly, please open an [issue](https://github.com/KorppuJauho/pocketts/issues) or a pull request that
changes the file. Small fixes are welcome.

## Words

TeamSpeak's own words (channel, poke, away, kick, ban, talk power, security level, push-to-talk and so on) follow the
translations of the TeamSpeak 5 desktop client, so TeamSpeak users find the words they know. Those translations are
made by their contributors and published under the MIT license:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation
> the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
> permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of
> the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
> THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
> TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

PocketTS's sentences are its own; only the terms were taken over.

## For translators

- Keep the placeholders: `%1$s` is a name or text, `%1$d` a number. Their order in the sentence can change.
- Plurals (`<plurals>`) have one form per number category of the language (Finnish: one, other; Russian: one, few,
  many, other; Chinese: other only).
- In Android's files, an apostrophe is written `\'` and a double quote `\"`. French uses the typographic `’`, which
  needs no escaping.
- "PocketTS", "TeamSpeak", "GitHub", "Opus Voice" and "Opus Music" stay as they are.
- A new English text added to `values/strings.xml` needs the same name in every language file, or the build fails.
