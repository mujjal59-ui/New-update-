LXR--AI COMPLETE VIDEO + YOUTUBE STUDIO PACKAGE
ব্যবহারের নির্দেশিকা (Google AI Studio)

এই প্যাকেজে দুটি আলাদা কিন্তু connected module সম্পূর্ণ করার implementation specification আছে:
1) Video Creation Studio — idea/script থেকে scene, visual production, editing, voice, render/export.
2) YouTube Video + SEO Studio — research, title, description, tags, hashtags, script, thumbnail, Shorts, SEO Package, preview/export.

গুরুত্বপূর্ণ: এই ZIP আপনার Android project-এর source code নয়। এটি আপনার project-এ কাজ করানোর prompt, specification ও reference asset bundle। আসল project ZIP আলাদাভাবে Google AI Studio-তে upload করতে হবে।

কীভাবে ব্যবহার করবেন
1. Google AI Studio-তে আপনার সর্বশেষ LXR--AI project ZIP upload করুন।
2. এই ZIP-এর 01_Prompts থেকে PROMPT_1 পাঠান। এটি inspection-only; কোনো code edit করতে নিষেধ করা হয়েছে।
3. Audit report ভালোভাবে দেখুন। ভুল/অবাস্তব API বা paid dependency থাকলে আগে ঠিক করুন।
4. অনুমোদনের পর PROMPT_2 পাঠান — Video Creation Studio implementation.
5. build/test report review করে PROMPT_3 পাঠান — YouTube Video + SEO Studio implementation.
6. প্রতিটি phase শেষে changed files, tests, build output এবং limitations যাচাই করুন। একসাথে তিনটি prompt পাঠাবেন না।

Provider role map
- FreeLLMAPI: SEO title, description, tags, script ও অন্যান্য text generation — configured LLM থাকলে।
- OmniRoute: task অনুযায়ী উপযুক্ত LLM route/select।
- Perplexity + You.com: research ও factual verification; source/citation ধরে রাখা।
- Phind: কেবল official, usable API যাচাই হলে optional provider।
- OpenMontage: verified video/visual production pipeline; actual capability audit করতে হবে।
- LXR--AI: সব ফলাফল একত্র করে editable SEO Package তৈরি।

বাস্তব সীমাবদ্ধতা
- FreeLLMAPI/OmniRoute নিজেরা text-to-video generator নয়।
- OpenMontage-এর কোনো workflow slideshow, stock footage, SVG animation বা FFmpeg render হলে সেটিকে generative AI video বলে দেখানো যাবে না।
- Text-to-video/image-to-video-এর জন্য আলাদা real provider/API লাগতে পারে।
- Perplexity/You.com/Phind/OpenMontage/OmniRoute-এর বর্তমান API, pricing, quota, terms এবং availability official docs থেকে implementation-এর সময় যাচাই করতে হবে।
- Phind-এর official working API না থাকলে integration disabled/omitted থাকবে।
- “100% SEO” বা guaranteed viral ফলাফল সম্ভব বলে দাবি করা যাবে না।
- Free quota unlimited নয়। কোনো paid service ব্যবহার করার আগে স্পষ্ট অনুমতি নিতে হবে।
- API key কখনো APK বা Android source-এ রাখবেন না; secure backend-এ রাখবেন।

প্যাকেজের ফাইল
01_Prompts/
  PROMPT_1_Project_Inspection_Video_YouTube.txt
  PROMPT_2_Complete_Video_Creation_Studio.txt
  PROMPT_3_Complete_YouTube_SEO_Studio.txt
  PROMPT_4_Combined_Provider_Architecture.txt
02_Reference_Images/
  আগের Dashboard ও Character reference images
03_Documentation/
  আগের Free LLM PDF এবং Video_YouTube_Studio_Implementation_Guide.pdf

সফলতা যাচাই
- পুরোনো feature ও saved data অক্ষত।
- Video Studio বাস্তবে supported production/render/export করতে পারে।
- YouTube Studio research citations-সহ editable SEO Package দেয়।
- provider failure/limit হলে graceful fallback।
- fake quota, fake progress, fake analytics বা fake video generation নেই।
- build succeeds এবং tests pass।
