# DIGITAL IRIS - build for the PlayStation target and the host preview.
#
# No SDK is involved: the console build is bare metal C plus one assembly
# startup file, linked with ps1.ld and packaged by tools/.  The host preview
# compiles the same scene sources against a software model of the GPU.

CROSS   ?= mipsel-linux-gnu-
PSCC    := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
PYTHON  ?= python3

NAME    := DIRIS

# R3000A, no FPU, no PIC, no small-data: a plain flat image.
PSARCH  := -march=mips1 -mtune=r3000 -mabi=32 -EL -msoft-float \
           -fno-pic -mno-abicalls -mno-llsc -G0 -fno-builtin \
           -ffreestanding -nostdlib -fomit-frame-pointer
PSCFLAGS:= $(PSARCH) -O2 -Iinclude -Wall -Wextra -Wno-unused-parameter \
           -ffunction-sections -fdata-sections -MMD -MP
PSLDFLAGS:= $(PSARCH) -T ps1.ld -Wl,--gc-sections -Wl,-Map,build/$(NAME).map

COMMON  := src/fixed.c src/tables.c src/render.c src/camera.c src/lighting.c \
           src/draw.c src/world.c src/iris.c src/grass.c src/particles.c \
           src/materials.c src/profiler.c src/scene.c
PSSRC   := $(COMMON) src/gte_ps1.c src/platform_ps1.c src/main_ps1.c
PSOBJ   := $(patsubst %.c,build/%.o,$(PSSRC)) build/src/crt0.o

HOSTSRC := $(COMMON) src/gte_host.c host/platform_host.c host/gpu_sim.c host/main_host.c
HOSTOBJ := $(patsubst %.c,build-host/%.o,$(HOSTSRC))
HOSTCFLAGS := -O2 -DDI_HOST -Iinclude -Ihost -Wall -Wextra -Wno-unused-parameter -MMD -MP

.PHONY: all ps1 host clean iso preview dirs
all: ps1 host

# ---------------------------------------------------------------- PS1 target
ps1: dist/$(NAME).EXE

build/%.o: %.c
	@mkdir -p $(dir $@)
	$(PSCC) $(PSCFLAGS) -c $< -o $@

build/src/crt0.o: src/crt0.s
	@mkdir -p $(dir $@)
	$(PSCC) $(PSARCH) -c $< -o $@

build/$(NAME).elf: $(PSOBJ) ps1.ld
	@mkdir -p build
	$(PSCC) $(PSLDFLAGS) $(PSOBJ) -o $@

dist/$(NAME).EXE: build/$(NAME).elf tools/mkexe.py
	@mkdir -p dist
	$(OBJCOPY) -O binary $< build/$(NAME).bin
	$(PYTHON) tools/mkexe.py build/$(NAME).elf build/$(NAME).bin $@

# ------------------------------------------------------------------ CD image
iso: dist/DIGITAL_IRIS.BIN
dist/DIGITAL_IRIS.BIN: dist/$(NAME).EXE tools/mkiso.py
	$(PYTHON) tools/mkiso.py --exe dist/$(NAME).EXE --name $(NAME).EXE \
	    --volume DIGITAL_IRIS --out dist/DIGITAL_IRIS

# --------------------------------------------------------------- host preview
host: build-host/preview

build-host/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(HOSTCFLAGS) -c $< -o $@

build-host/preview: $(HOSTOBJ)
	$(CC) $(HOSTOBJ) -o $@

preview: host
	@mkdir -p preview
	./build-host/preview --out preview --stats \
	    --times 3000,15000,25000,45000,60000,70000,83000,90000,100000,110000,118000
	@for f in preview/*.ppm; do convert "$$f" "$${f%.ppm}.png"; done
	@echo "frames in preview/"

src/tables.c: tools/gen_tables.py
	$(PYTHON) tools/gen_tables.py $@

clean:
	rm -rf build build-host preview dist/$(NAME).EXE dist/DIGITAL_IRIS.*

-include $(PSOBJ:.o=.d) $(HOSTOBJ:.o=.d)
