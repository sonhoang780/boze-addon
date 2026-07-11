package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Asks one random physics/biology/everyday-life trivia question in chat every time the
 * module is enabled (prefixed with "$"), and plays Can-You-Hear-The-Music.ogg for the
 * duration -- stopped the instant the module is disabled. ~2s after asking, a free
 * keyless AI text endpoint (pollinations.ai -- no API key/account needed, unlike
 * OpenAI/DeepSeek/etc.) answers it, also prefixed with "$".
 */
public class HoodResearch extends AddonModule {
    public static final HoodResearch INSTANCE = new HoodResearch();

    private static final Identifier MUSIC_ID = Identifier.fromNamespaceAndPath("example-addon", "hoodresearch_music");
    private static final SoundEvent MUSIC_SOUND = SoundEvent.createVariableRangeEvent(MUSIC_ID);

    private static final String[] QUESTIONS = {
        "Which is faster, light or sound?",
        "Does Earth is full of water?",
        "Are all animals twin?",
        "Can fish drown in water?",
        "Is the sun a planet?",
        "Do humans have more bones than a shark?",
        "Which is heavier, a kilogram of feathers or a kilogram of iron?",
        "Can you hear sound in space?",
        "Is lightning hotter than the surface of the sun?",
        "Do all birds can fly?",
        "Is glass a liquid or a solid?",
        "Does the moon make its own light?",
        "Can plants breathe at night?",
        "Is a tomato a fruit or a vegetable?",
        "Does hot water freeze faster than cold water?",
        "Do goldfish have a three-second memory?",
        "Is Mount Everest the closest point to the moon?",
        "Can humans see all colors that exist?",
        "Do bats are blind?",
        "Is blood blue inside the body?",
        "Does swallowed gum stay in your stomach for 7 years?",
        "Can a group of crows count as a family?",
        "Is Pluto still count as a planet?",
        "Do camels store water in their humps?",
        "Can you taste food properly with a blocked nose?",
        "Is the Great Wall of China visible from space?",
        "Do sharks need to keep swimming or they die?",
        "Is the human body mostly made of water?",
        "Can octopuses have three hearts?",
        "Does the Earth's core is made of solid metal?",
        "Is a second on Earth the same everywhere in the universe?",
        "Do penguins live at the North Pole?",
        "Can a chicken lay an egg without a rooster around?",
        "Is the ozone layer part of the atmosphere?",
        "Does a compass always point to true north?",
        "Can humans survive without a spleen?",
        "Is honey the only food that never spoils?",
        "Do dolphins sleep with one eye open?",
        "Is Venus the hottest planet in the solar system?",
        "Can you get sunburned on a cloudy day?",
        "Do all mammals give live birth?",
        "Is a spider considered an insect?",
        "Does the human heart stop when you sneeze?",
        "Can ice be colder than -40 degrees?",
        "Is Jupiter bigger than all other planets combined?",
        "Do snakes have eyelids?",
        "Is sound faster underwater than in air?",
        "Can humans regrow a lost fingernail?",
        "Does caffeine affect everyone the same way?",
        "Is a year on Mercury shorter than a day on Mercury?",
        "Can a broken bone become stronger after healing?",
        "Which is faster, sound or light?",
        "What is the speed of light in a vacuum?",
        "Why is the sky blue?",
        "How does gravity work?",
        "What is a black hole?",
        "Can anything travel faster than light?",
        "What is quantum entanglement?",
        "How does a magnet work?",
        "Why does ice float on water?",
        "What is the difference between mass and weight?",
        "How do airplanes fly?",
        "What is dark matter?",
        "What is dark energy?",
        "How does a nuclear reactor produce energy?",
        "What is the theory of relativity?",
        "Can time travel be possible?",
        "What causes a rainbow?",
        "Why do stars twinkle?",
        "What is absolute zero?",
        "How does a laser work?",
        "What is string theory?",
        "Why does the moon cause tides?",
        "What is antimatter?",
        "How is electricity generated?",
        "What is the Doppler effect?",
        "How do solar panels convert sunlight to electricity?",
        "What is the Higgs boson?",
        "Why is the ocean blue?",
        "What happens if you fall into a black hole?",
        "How do mirrors reflect light?",
        "What is friction?",
        "Why do objects fall at the same rate in a vacuum?",
        "What is a wormhole?",
        "How does a compass work?",
        "What is the conservation of energy?",
        "How do microwave ovens heat food?",
        "What is the electromagnetic spectrum?",
        "Why is space silent?",
        "What is inertia?",
        "How do submarines dive and surface?",
        "What is thermodynamics?",
        "Why do hot air balloons rise?",
        "What is plasma?",
        "How does fiber optics work?",
        "What causes lightning?",
        "Why does a prism separate white light?",
        "What is centrifugal force?",
        "How do gyroscopes work?",
        "What is radioactivity?",
        "Why does metal feel colder than wood at room temperature?",
        "What is a supernova?",
        "How do sound waves travel?",
        "What is the Coriolis effect?",
        "Why do atoms bond together?",
        "What is surface tension?",
        "How does an electric motor work?",
        "What is a semiconductor?",
        "Why do planets orbit the sun?",
        "What is cosmic microwave background radiation?",
        "How does an MRI machine work?",
        "What is the difference between AC and DC current?",
        "Why does water boil at a lower temperature at high altitudes?",
        "What is the speed of sound?",
        "How do bulletproof vests work?",
        "What is the escape velocity of Earth?",
        "Why are raindrops tear-shaped? (They aren't, why do people think they are?)",
        "What is Schrödinger's cat paradox?",
        "How does sonar work?",
        "What is a neutron star?",
        "Why does static electricity shock you?",
        "What is the uncertainty principle?",
        "How do batteries store energy?",
        "What is a superconductor?",
        "Why do we see the same side of the moon always?",
        "What is the photoelectric effect?",
        "How does a telescope work?",
        "What is the half-life of a radioactive element?",
        "Why does sound travel faster in water than in air?",
        "What is a perpetual motion machine, and why is it impossible?",
        "How does an atomic bomb work?",
        "What is the center of gravity?",
        "Why do boomerangs come back?",
        "What is aerodynamic drag?",
        "How do parachutes slow you down?",
        "What is a light-year?",
        "Why is the core of the Earth hot?",
        "What is a quasar?",
        "How does a pendulum clock keep time?",
        "What is the inverse square law?",
        "Why do bubbles pop?",
        "What is an isotope?",
        "How does an X-ray work?",
        "What is the strong nuclear force?",
        "Why does a whip make a cracking sound?",
        "What is a quark?",
        "How do LEDs produce light?",
        "What is the weak nuclear force?",
        "Why does a spinning top stay upright?",
        "What is time dilation?",
        "How does a thermometer measure temperature?",
        "What is a gravitational wave?",
        "Why do ice skates glide?",
        "What is the Pauli exclusion principle?",
        "How do noise-canceling headphones work?",
        "What is the difference between fission and fusion?",
        "Why does helium make your voice high-pitched?",
        "What is a pulsar?",
        "How do touchscreens work?",
        "What is the difference between a scalar and a vector?",
        "Why is glass transparent?",
        "What is standard atmospheric pressure?",
        "How does an antenna broadcast signals?",
        "What is the Mach number?",
        "Why do tires have treads?",
        "What is a photon?",
        "How does an electron microscope work?",
        "What is the concept of entropy?",
        "Why does a curveball curve?",
        "What is the event horizon?",
        "How do transformers change voltage?",
        "What is a tachyon?",
        "Why do astronauts float in space?",
        "What is the multiverse theory?",
        "How does a barometer work?",
        "What is the principle of buoyancy?",
        "Is the Hairy Ball theorem physically true?",
        "What is infinity?",
        "Is zero an even or odd number?",
        "What is the Pythagorean theorem?",
        "How do you calculate the area of a circle?",
        "What is the Golden Ratio?",
        "Are there different sizes of infinity?",
        "What is the Fibonacci sequence?",
        "Why can't you divide by zero?",
        "What is a prime number?",
        "How many digits of Pi are known?",
        "What is Fermat's Last Theorem?",
        "What is the Monty Hall problem?",
        "How does probability work?",
        "What is a fractal?",
        "Why do prime numbers matter in cryptography?",
        "What is Euler's number ($e$)?",
        "How does calculus help in everyday life?",
        "What is the Riemann Hypothesis?",
        "What is a geometric progression?",
        "How do you solve a quadratic equation?",
        "What is a logarithm?",
        "What is the Prisoner's Dilemma?",
        "How many sides does a tesseract have?",
        "What is Zeno's Paradox?",
        "Why is a triangle the strongest shape?",
        "What is a matrix in algebra?",
        "How do you calculate standard deviation?",
        "What is the Banach-Tarski paradox?",
        "What is the concept of a limit in calculus?",
        "How does a slide rule work?",
        "What is non-Euclidean geometry?",
        "What is Game Theory?",
        "How do you find the derivative of a function?",
        "What is a Möbius strip?",
        "Why is 1 not considered a prime number?",
        "What is a Venn diagram?",
        "How is trigonometry used in construction?",
        "What is a vector space?",
        "What is the Birthday Paradox?",
        "How do you calculate permutations and combinations?",
        "What is an imaginary number?",
        "What is the Collatz conjecture?",
        "How does exponential growth work?",
        "What is a polyomino?",
        "What is the four-color theorem?",
        "How do you calculate compound interest?",
        "What is topology?",
        "What is a Klein bottle?",
        "How do prime factorization algorithms work?",
        "What is the difference between mean, median, and mode?",
        "What is absolute value?",
        "How do you integrate a function?",
        "What is the Pigeonhole Principle?",
        "What is a Taylor series?",
        "How does the decimal system work?",
        "What is binary code?",
        "What is an irrational number?",
        "How do you prove that the square root of 2 is irrational?",
        "What is Pascal's Triangle?",
        "What is a hypotenuse?",
        "How do you solve a system of linear equations?",
        "What is a Boolean algebra?",
        "What is the traveling salesman problem?",
        "How does a Fourier transform work?",
        "What is a normal distribution curve?",
        "What is chaos theory?",
        "How do you find the volume of a sphere?",
        "What is a determinant?",
        "What is modular arithmetic?",
        "How is math used in music?",
        "What is the Law of Large Numbers?",
        "What is a scalar quantity in math?",
        "How do you define an asymptote?",
        "What is an algorithm?",
        "What is the difference between discrete and continuous mathematics?",
        "How do you calculate the surface area of a cylinder?",
        "What is a perfect number?",
        "What is the Goldbach conjecture?",
        "How do Roman numerals work?",
        "What is a set in mathematics?",
        "What is an axiom?",
        "How do you measure an angle in radians?",
        "What is the sine wave?",
        "What is group theory?",
        "How do logarithms convert multiplication into addition?",
        "What is the concept of probability density?",
        "What is a polynomial?",
        "How do mathematical proofs work?",
        "What is the Cartesian coordinate system?",
        "What is a paradox?",
        "How do base-16 (hexadecimal) numbers work?",
        "What is a prime triplet?",
        "What is the difference between a theorem and a lemma?",
        "How is pi calculated?",
        "What is linear programming?",
        "What is a cyclic polygon?",
        "How do you use the quadratic formula?",
        "What is a composite number?",
        "What is the law of cosines?",
        "How do logic gates relate to math?",
        "What is an ordinal number?",
        "What is the difference between correlation and causation?",
        "How do you calculate a factorial?",
        "What is graph theory?",
        "What is a complex number?",
        "How do you read a scientific notation?",
        "What is an affine transformation?",
        "What is a binomial coefficient?",
        "How does the abacus work?",
        "What is a differential equation?",
        "What is the concept of isomorphism?",
        "How is the area of a polygon calculated?",
        "What is the fundamental theorem of algebra?",
        "What is an integer?",
        "How does statistics predict election outcomes?",
        "What is a magic square?",
        "What is mathematical induction?",
        "How do tessellations work?",
        "What is the Mandelbrot set?",
        "What is a non-linear equation?",
        "How do you calculate the perimeter of a rectangle?",
        "What is the quotient rule in calculus?",
        "What is a stochastic process?",
        "How does geometry apply to architecture?",
        "Are all animals born as twins?",
        "What is DNA?",
        "How does evolution work?",
        "What is the periodic table?",
        "How do vaccines work?",
        "Why do leaves change color in the fall?",
        "What is photosynthesis?",
        "How do viruses reproduce?",
        "What is global warming?",
        "How do tectonic plates move?",
        "What is the water cycle?",
        "Why do we sleep?",
        "How does the human brain store memories?",
        "What is a stem cell?",
        "How do antibiotics kill bacteria?",
        "What is the greenhouse effect?",
        "Why do animals hibernate?",
        "How do bees make honey?",
        "What is genetic engineering?",
        "How do volcanoes erupt?",
        "What is an ecosystem?",
        "Why is blood red?",
        "How do fish breathe underwater?",
        "What is the ozone layer?",
        "How does digestion work?",
        "What is natural selection?",
        "Why do snakes shed their skin?",
        "How do birds migrate across the globe?",
        "What is a black widow spider's venom made of?",
        "How are fossils formed?",
        "What is the function of the human appendix?",
        "Why do some plants eat insects?",
        "How do bats navigate in the dark?",
        "What is cloning?",
        "How do red and white blood cells differ?",
        "What is the Richter scale?",
        "Why do we dream?",
        "How does the immune system remember diseases?",
        "What is a mutation?",
        "How do trees transport water from roots to leaves?",
        "What is plate tectonics?",
        "Why are flamingos pink?",
        "How do penguins survive the cold?",
        "What is an enzyme?",
        "How does coral bleaching happen?",
        "What is a biome?",
        "Why do humans have different blood types?",
        "How do spiders spin webs?",
        "What is the difference between a virus and a bacterium?",
        "How do chameleons change color?",
        "What is the carbon cycle?",
        "Why do owls have silent flight?",
        "How does a caterpillar turn into a butterfly?",
        "What is a symbiotic relationship?",
        "How do whales communicate?",
        "What is a dominant trait in genetics?",
        "Why are some animals nocturnal?",
        "How do mushrooms grow?",
        "What is the nitrogen cycle?",
        "How does a squid produce ink?",
        "What is the human genome project?",
        "Why do we sneeze?",
        "How do fireflies glow?",
        "What is biodiversity?",
        "How do kidneys filter blood?",
        "What is an invasive species?",
        "Why do humans sweat?",
        "How do geckos climb smooth walls?",
        "What is a prion?",
        "How do roots break through concrete?",
        "What is the scientific method?",
        "Why are tears salty?",
        "How do electric eels generate a shock?",
        "What is a neurotransmitter?",
        "How do dolphins sleep without drowning?",
        "What is epigenetics?",
        "Why do men have nipples?",
        "How does a starfish regenerate its arms?",
        "What is artificial intelligence in biology?",
        "How do ants build complex colonies?",
        "What is the placebo effect?",
        "Why do onions make you cry?",
        "How does a snake digest a whole animal?",
        "What is the purpose of tonsils?",
        "How do humans maintain body temperature?",
        "What is a reflex action?",
        "Why do dogs have a better sense of smell than humans?",
        "How do coral reefs form?",
        "What is an extremophile?",
        "How do vaccines cause herd immunity?",
        "What is a pheromone?",
        "Why are most plants green?",
        "How do sloths survive with such a slow metabolism?",
        "What is the role of the liver?",
        "How does the eye focus on near and far objects?",
        "What is a keystone species?",
        "Why do we get goosebumps?",
        "How do carnivorous plants digest their prey?",
        "What is the difference between venom and poison?",
        "How do cells divide?",
        "What is a circadian rhythm?",
        "Why do scorpions glow under UV light?",
        "How do lungs exchange oxygen and carbon dioxide?",
        "What is the function of cholesterol?",
        "How do birds sing complex songs?",
        "What is the origin of life?",
        "Why do some animals have scales instead of fur?",
        "How do hormones affect behavior?",
        "What is a genome?",
        "How do the senses of taste and smell interact?",
        "What is a dominant and recessive gene?",
        "Why do we feel pain?",
        "How do antibiotics affect the microbiome?",
        "What is the difference between mitosis and meiosis?",
        "How do deep-sea creatures survive immense pressure?",
        "What is the role of white blood cells?",
        "Why do humans have wisdom teeth?",
        "How do animals learn behaviors?",
        "What is a food chain?",
        "How do desert animals conserve water?",
        "What is the theory of panspermia?",
        "Why do cuttlefish change their skin texture?",
        "How do we digest lactose?",
        "What is the function of the spleen?",
        "How does a virus mutate?",
        "Why does bread rise?",
        "How does soap clean your hands?",
        "Why do we cook food?",
        "How does a refrigerator keep things cold?",
        "Why do apples turn brown when cut?",
        "How does a flush toilet work?",
        "Why do clothes shrink in the wash?",
        "How do non-stick pans work?",
        "Why does salt melt ice on roads?",
        "How does sunscreen protect your skin?",
        "Why do we put yeast in pizza dough?",
        "How does a zipper work?",
        "Why do shoes smell?",
        "How does a barcode scanner read a label?",
        "Why does coffee keep you awake?",
        "How does baking soda neutralize odors?",
        "Why do mirrors fog up during a shower?",
        "How does a ballpoint pen work?",
        "Why does warm water freeze faster than cold water (Mpemba effect)?",
        "How do erasers remove pencil marks?",
        "Why do we yawn?",
        "How does an air conditioner cool a room?",
        "Why does spicy food make you sweat?",
        "How does a vacuum cleaner create suction?",
        "Why do batteries degrade over time?",
        "How does a lock and key work?",
        "Why does paper get transparent when oily?",
        "How do sunglasses block UV rays?",
        "Why does blowing on hot food cool it down?",
        "How does a microwave heat food evenly?",
        "Why do wet clothes look darker?",
        "How does a remote control change channels?",
        "Why do we use toothpaste?",
        "How does a matches strike light?",
        "Why does mint taste cold?",
        "How does a quartz clock keep time?",
        "Why do plastic containers get stained by tomato sauce?",
        "How does a stapler work?",
        "Why do fingers wrinkle in water?",
        "How does a toaster pop up?",
        "Why does sugar dissolve faster in hot water?",
        "How does a lightbulb produce light?",
        "Why do tires go flat over time?",
        "How does a digital camera capture an image?",
        "Why does opening a window create a draft?",
        "How does a smoke detector work?",
        "Why do we cry when cutting onions?",
        "How does a car engine work?",
        "Why does rust form on metal?",
        "How does an umbrella repel water?",
        "Why do rubber bands stretch?",
        "How does a hair dryer produce heat?",
        "Why does hard water leave stains?",
        "How does a pressure cooker cook food faster?",
        "Why do we use baking powder in cakes?",
        "How does a mechanical pencil feed lead?",
        "Why does dust accumulate in corners?",
        "How does a bicycle stay balanced?",
        "Why do static shocks happen more in winter?",
        "How does a washing machine clean clothes?",
        "Why does bleach remove stains?",
        "How does a battery charger work?",
        "Why do glasses get scratched?",
        "How does a fire extinguisher put out a fire?",
        "Why does milk curdle when lemon is added?",
        "How does a guitar produce different notes?",
        "Why do ice cubes crack when dropped in a drink?",
        "How does a zipper get stuck?",
        "Why does leather stiffen when wet?",
        "How does a thermostat control temperature?",
        "Why do carbonated drinks fizz?",
        "How does a water filter remove impurities?",
        "Why does superglue bond instantly?",
        "How does a syringe work?",
        "Why does old paper turn yellow?",
        "How does a septic tank function?",
        "Why does garlic leave a lingering smell?",
        "How does a hearing aid amplify sound?",
        "Why does sweat smell?",
        "How does a dehumidifier extract moisture?",
        "Why does boiling pasta water overflow?",
        "How does a lint roller grab hair?",
        "Why do scissors become dull?",
        "How does an electric kettle turn itself off?",
        "Why does wood burn but rocks do not?",
        "How does a sponge hold water?",
        "Why does touching a hot pan burn you?",
        "How does a dimmer switch adjust lighting?",
        "Why does tape lose its stickiness?",
        "How does a water heater work?",
        "Why does dropping a phone crack the screen?",
        "How does a blender crush ice?",
        "Why does a mattress sag over time?",
        "How does a cork puller work?",
        "Why does perfume fade throughout the day?",
        "How does a paper shredder cut paper?",
        "Why do books get dusty on shelves?",
        "How does an induction cooktop heat pots?",
        "Why do shoes wear out at the heel first?",
        "How does a sprinkler system rotate?",
        "Why does mold grow on bread?",
        "How does a fan make you feel cooler?",
        "Why does paint dry?",
        "How does a padlock mechanism work?",
        "Why does stepping on a Lego hurt so much?",
        "How does a garage door opener work?",
        "Why does foil spark in a microwave?",
        "How does a thermometer read a fever?",
        "Why does hair turn gray?",
        "How does a dishwasher clean dishes?",
        "Why do contact lenses dry out?",
        "How does a measuring tape lock in place?",
        "Why does chewing gum lose its flavor?",
        "How does a seatbelt lock during a sudden stop?",
        "Why does cooking meat make it shrink?",
        "How does an escalator move continuously?",
        "Why does fabric softener reduce static?",
        "How does a coffee maker brew coffee?",
        "Why does a bruise change colors?",
        "How does a flushometer work on commercial toilets?",
        "Why does leaving the fridge open not cool a room?",
        "How does a memory foam pillow work?",
        "Why does biting your tongue hurt so much?",
        "How does a snowblower throw snow?",
        "Why do candles burn down instead of out?"
    };

    private final Random random = new Random();
    private SoundInstance currentMusic;

    public HoodResearch() {
        super("HoodResearch", "Asks a random physics/biology/life trivia question and plays music while enabled.");
    }

    @Override
    public void onEnable() {
        String question = QUESTIONS[random.nextInt(QUESTIONS.length)];
        Minecraft mc = Minecraft.getInstance();
        // Real outgoing chat message (goes to the server, which broadcasts it like any
        // normal player message) -- not ChatHelper/sendSystemMessage, which is a purely
        // LOCAL client-side overlay only the local player ever sees. This is the only
        // way for other players on the server to see it at all.
        if (mc.getConnection() != null && mc.getConnection().getConnection().isConnected()) {
            mc.getConnection().sendChat("$ Q: " + question);
        }
        askAiAndAnswer(question);

        // MASTER category so it plays regardless of Minecraft's own "Music" volume
        // slider (per explicit request) -- forMusic() hardcodes SoundSource.MUSIC,
        // which silently produces no sound at all when that slider is at 0%, with no
        // error/crash to indicate why. Built via the full constructor instead so the
        // category can be overridden while keeping forMusic()'s other defaults
        // (volume/pitch 1.0, non-looping, Attenuation.NONE, relative to the listener).
        currentMusic = new SimpleSoundInstance(MUSIC_ID, SoundSource.MASTER, 1.0f, 1.0f,
            RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
        mc.getSoundManager().play(currentMusic);
    }

    @Override
    public void onDisable() {
        if (currentMusic != null) {
            Minecraft.getInstance().getSoundManager().stop(currentMusic);
            currentMusic = null;
        }
    }

    // Off the render thread entirely (network call + sleep) -- hopping back to the
    // main thread only for the actual sendChat calls, same pattern as
    // StashWebhook#send/SpotifyIntegration's HttpURLConnection calls elsewhere in this
    // addon. A full lecture-style answer never fits one chat line (server enforces a
    // ~256-char cap per message), so it's split into several messages and sent one at
    // a time with a pause between each -- reads like someone actually talking through
    // a speech instead of a single wall of text, and avoids most anti-spam heuristics
    // that flag a burst of same-tick messages.
    private static final int CHAT_LINE_MAX = 200;
    private static final long LINE_DELAY_MS = 1500L;

    private void askAiAndAnswer(String question) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                return;
            }
            String answer = fetchAiAnswer(question);
            java.util.List<String> lines = splitIntoChatLines(answer);
            for (int i = 0; i < lines.size(); i++) {
                // "A: " only on the first line -- continuation lines are the same
                // sentence spilling over, repeating it on every line would read wrong.
                String prefix = i == 0 ? "$ A: " : "$ ";
                String line = prefix + lines.get(i);
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    // The multi-line answer sequence spans many seconds (2s initial delay
                    // + up to several LINE_DELAY_MS gaps) -- if the player disconnects
                    // mid-sequence, a scheduled mc.execute() here can still fire against a
                    // ClientPacketListener object that hasn't been nulled out yet even
                    // though its underlying netty channel already tore down, sending a
                    // packet into a dead channel (observed as a NullPointerException("msg")
                    // deep in Connection.doSendPacket/Netty's own async task executor).
                    // Checking the raw Connection's isConnected(), not just the listener
                    // reference, matches the same guard EBouncePlus#flushPackets already
                    // uses for exactly this reason.
                    if (mc.getConnection() != null && mc.getConnection().getConnection().isConnected()) {
                        mc.getConnection().sendChat(line);
                    }
                });
                try {
                    Thread.sleep(LINE_DELAY_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
    }

    /** Breaks a long answer into <=CHAT_LINE_MAX-char chunks, only at word boundaries. */
    private static java.util.List<String> splitIntoChatLines(String text) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String remaining = text.trim();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= CHAT_LINE_MAX) {
                lines.add(remaining);
                break;
            }
            int cut = remaining.lastIndexOf(' ', CHAT_LINE_MAX);
            if (cut <= 0) cut = CHAT_LINE_MAX; // no space found -- hard cut, rare
            lines.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        return lines;
    }

    /**
     * pollinations.ai's plain-GET text endpoint -- free, no API key/signup, unlike
     * OpenAI/DeepSeek/Anthropic etc. Returns raw text for the prompt in the URL path.
     * A free/shared endpoint like this returns the occasional transient 502/503 under
     * load (observed in practice) -- a couple of quick retries before giving up.
     */
    private static String fetchAiAnswer(String question) {
        String prompt = "Give a detailed, thorough, lecture-style explanation answering this "
            + "question, as if delivering a short speech (several sentences, real substance, "
            + "not a one-liner): " + question;
        URI uri = URI.create("https://text.pollinations.ai/" + URLEncoder.encode(prompt, StandardCharsets.UTF_8));

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(30000);
                String raw;
                try (InputStream is = conn.getInputStream()) {
                    raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                // Chat lines are single-line -- collapse newlines to spaces;
                // splitIntoChatLines handles breaking the result into multiple messages.
                return raw.replaceAll("\\s*\\n+\\s*", " ").trim();
            } catch (Exception e) {
                lastError = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }
        return "(AI answer unavailable: " + lastError + ")";
    }
}
