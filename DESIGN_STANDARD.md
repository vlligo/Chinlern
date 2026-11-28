# Chinlern design standard

Use this standard for Android screens, shared controls, the stroke-order viewer, and app branding. Keep user-facing interface copy in Russian.

## Color palette

| Color | Hex | Use |
| --- | --- | --- |
| Burgundy red | `#9F2936` | Primary action fills with white text, active navigation icons and labels, and error or incorrect-answer feedback. Pair feedback with a clear message or symbol. |
| Jade green | `#244B44` | Secondary actions, prominent study prompts and headings, selected checkboxes, and correct or completed states. Use pale jade `#EAEFE4` for secondary button fills and correct-answer backgrounds. |
| Gold | `#AD7E29` | Thin accents, progress indicators, the calendar’s today marker, and keyboard focus in the stroke-order viewer. Use the light gold wash `#F4E4BA` for reader highlights and progress tracks. |

Use paper `#F9F6EE` for the app background, white `#FFFFFF` for cards, and warm white `#FFFCF7` for reading surfaces. Use ink `#2B302B` for body text and muted ink `#696D63` for secondary text. Use blush `#F7E8E1` for soft red accents. Use dark launch red `#811924` for the launch system bars. The existing brand mark uses bright red `#C52632`, gold `#FFD66B`, and dark red book details `#A91E2C`. Use `#FFD66B` for the highlighted count on the jade study summary card as well.

Use the existing theme and color values when extending the app. Give each state a readable label, recognizable symbol, or accessible state so its meaning is available without relying on color alone.

## Minimal controls

- Replace a visible label with a familiar icon or symbol when the action is clear in context. Use the existing symbols for actions such as back, play audio, and more options.
- Leave standalone icons visually bare. Keep their touch targets, pressed feedback, and keyboard focus, and omit decorative circles, outlines, pills, and filled backgrounds around the glyph.
- Give icon-only controls a Russian accessibility label and a touch target of at least 48 × 48 dp. Keep visible text when a symbol would make the action ambiguous or hide an important consequence. Show what a destructive action affects in its confirmation.

## Reuse existing interfaces

Reuse the app’s existing screens, sheets, dialogs, cards, and navigation patterns wherever they can support the task. Extend the shared component when the same change belongs in several places. Introduce a new interface only when explicitly requested.

## PRC and CCP references

Keep the existing red-and-gold five-star book mark in its established placements on the launcher, app header, and study summary card. Reserve the flag, state emblem, and other official symbols of the People’s Republic of China (PRC), as well as the Chinese Communist Party (CCP) flag, emblem, and leader portraits, for relevant historical, cultural, or language-learning material. Identify the state or party accurately in captions and use explicit language labels for language controls. Avoid political symbols as decoration, navigation icons, or rewards.

Keep the existing `好好学习，天天向上` quotation on the streak card, with the Russian translation and attribution to Mao Zedong. Use additional quotations only when they serve a language lesson or provide historical context. Include the original Chinese, a Russian translation, and a reliable source. Mark disputed attribution as such. Keep political quotations out of routine prompts, notifications, and rewards. Preserve references in user-provided texts and dictionary entries as part of the source material.
