
# Example Addon

Boze-addon

An utility addon with unique features. Recommend using Window x64 only.

Modules:

AntiMace — Place a block as soon as the enemy flies down to you

AntiPiston - Break/Block Crystal and Piston

AutoAccept - Auto /tpaccept the sender of an incoming tpa request; retries with /tpy if the server doesn't recognize tpaccept

AutoPortal — Builds a portal for you

AutoShop - Automates the server /shop GUI to buy totems, end crystals, or exp bottles (kingmc.vn only)

AutoWalk - Holds forward/backward movement every tick; auto-pauses while Baritone is driving (e.g. EBounce+'s ObstaclePassing)

AuraStep - Fire/Water steps behind you

BedAura - Automated bed-clutch PvP: targets, predicts, places, and detonates beds in the nether/end

BetterBasePlace - Places obisidan if there is no blocks to place crystal

BetterChams - Extended Version of Boze's Chams with a lot of unique features

BetterOffhand - Keeps a totem in the offhand and swaps in an apple while sword-fighting healthy

Bubble - cover yourself with a bubble

ChestButtons - Allows you to steal/put in items even if its finished putting in/stealing

ChestScan - Cover opened chest with a colored box so you know which is full and which is not

- Red is Full
- Green is Empty
- Yellow is neither Full nor Empty

ControlRocket - Control Fireworks but supports FakeFly mode (havent tested yet)

CustomSky - Changed your default sky with Image and frag Shaders

Dummy - Fakeplayer but loses hp and pops totems

EbookReader - Insert eupb files to read ebook inside minecraft

EbouncePlus - Elytra Recast (to not lose durability turn off FakeLag)

ElytraFix - Fix your Elytra on Air (useful when AFK travelling)

EvilRekit - Steal items (even with different names which were saved in kits)

FastWeb - Move through cobwebs at a configurable speed instead of vanilla's crawl

GameAnimation - Ease-out-cubic smoothing for UI transitions (e.g. Whitelist hover highlight)

GifHUD - Import gif links and display it on screen

HoleSnap - Auto-walks into the nearest 1x1/2x1/2x2 hole and snaps into place

HoodResearch - ask 500+ questions to boze chat and let random AI answer

HUDEditor - For MusicHUD and GifHUD

IgnoreClimb - Ignores vines/ladders/scaffolding, walk past them like normal blocks instead of climbing

InfiniteChat - Keeps unlimited chat history instead of the vanilla 100-message cap

InventoryCleaner - Throw out worse items and blacklisted items

InventorySorter - Sort your items in inventory based on saved kits

InvMovePlus - A patch for Boze Invmove (stop you moving for a tick when interacting with items)

KillEffect - Bursts dying players into glowing ghost dust instead of the vanilla death animation

LoadingScreen - Change your default main menu with customizable Intro and Background video

MainHand - Instant totem re-hold on pop + apple-to-offhand + low-health hand snap

MoreKnockback - Sprint-reset W-taps your melee hits for extra knockback (skips crits)

MusicHUD - Shows current songs with extra features

NoSlow - Move at full speed while using an item (per-anticheat bypass)

PathFinder - Auto-elytrafly pathing so you don't waste fireworks (still need at least 5 fireworks for baritone to work)

PearlPhase - Throw an ender pearl to phase/clip into the block under your feet

PenisESP - length of dih based on timezone, for japan it is censored

PhobosAutoTotem - Keeps the right item in the offhand (totem on low HP, gapple while fighting) (kingmc.vn only)

PhobosDoubleHand - Silently holds a totem slot on danger, Rage/Legit modes (kingmc.vn only)

PistonAura - Fully automatic piston-crystal aura

PistonPush - Places a piston above a holed target and powers it to shove them out

PlayMusic - Play Music inside minecraft (default prefix is >, use >login to login to your youtube's account)
To skip/next use arrow left and arrow right; to increase/decrease volumde use up and down arrows

Replenish - Auto-refill hotbar item stacks from your inventory

SelfWeb - Web your head

SpotifyIntegration - Sync your song playing on Spotify with MusicHUD (Requires Spotify Premium)

StashArrange - Arrange shulker boxes in an open chest by name (special, number, alphabet)

StashFinder - BaseHunting

TargetESP - Russian bloat targetESP

Trails - Dashed trail of fading dots behind you while moving or turning in 3rd person

TungTungSahur - Summons TungTung

Velocity - NoPush toggles + incoming-knockback cancel (Normal/Grim)

WebBrowser - Surf the internet inside minecraft

Notification - Prints [+]/[-] Module lines in chat for every module toggle. KeepNoti option (on by default): turn off to replace a module's previous toggle line with its newest one instead of stacking both

Extensions:
VersionHUD in Boze HUD (show boze-addon latest version)

Commands:
prefix for commands is boze prefix (default would be .)

.itemdrop all

.itemdrop dropitem <items>

.kit save <name> : save a kit

.kit load <name> : load a kit

.kit delete <name> : delete a kit

.kit list : list saved kits

.kit active : show/set the currently active kit

.printmodule <module> : print a module's internal state

.printoptions <module> : print a module's options and their current values

.set <module> <bindoption> <bind> : set a module's bind option

.cfg all save <name> : save every module's settings as a named profile

.cfg all load <name> : load a named config profile

.cfg all delete <name> : delete a named config profile

.stashfinder webhook <url> — set Discord webhook URL

.stashfinder userid <id> — set user ID to ping you when detected a stash

.stashfinder test — send test webhook (rate-limit once/5s)

.stashfinder reset — delete stashfinder history, reset scanned chunks

.research key <Groq API key> to add Groq AI to answer questions.
